# Android Zhuan Marketing Image LRU Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android Zhuan resolve Armada image references from shared Redis, normalize each source image once per process, and reuse prepared JPEG main/thumbnail bytes from a fixed 64MB sliding-expiry LRU.

**Architecture:** Extend the existing Kafka image DTO with a strict `{assetRef}` contract, then place a Redis-backed singleflight loader and a byte-weighted process LRU between command parsing and the native sender. The sender passes immutable prepared bytes through WaApp/node APIs, so per-group work starts at WhatsApp media encryption/upload rather than Base64 or pixel conversion; pre-send transient failures safely release the command claim.

**Tech Stack:** Go 1.25, `go-redis/v9`, `singleflight`, `container/list`, `disintegration/imaging`, standard `image/jpeg`, miniredis, zap, standard `testing`.

---

**Design reference:** `../armada/docs/superpowers/specs/2026-07-19-android-marketing-image-redis-lru-design.md`

**Companion plan:** `../armada/docs/superpowers/plans/2026-07-19-android-marketing-image-redis-lru-armada.md`

**Execution boundary:** Execute inside an isolated worktree of `whatsapp-server-feature-android-zhuan`. Read its `AGENTS.md` before editing and run the mandatory `gofmt`, `go vet`, `go build`, `go test`, and targeted `go test -race` gates.

## File map

Create:

- `internal/armada/image_asset_cache.go` — fixed-capacity byte-weighted LRU with expire-after-access and stats.
- `internal/armada/image_asset_cache_test.go` — capacity, access renewal, expiry, and concurrent safety.
- `internal/armada/image_asset_normalizer.go` — pixel cap, orientation, resize, white compositing, and two JPEG outputs.
- `internal/armada/image_asset_normalizer_test.go` — dimensions, transparency, invalid input, and 5MB result guard.
- `internal/armada/image_asset_loader.go` — Redis key derivation, integrity checks, singleflight, LRU, legacy Base64 bridge, and classified errors.
- `internal/armada/image_asset_loader_test.go` — Redis miss/corruption/transient errors and one-load concurrency.

Modify:

- `internal/armada/message_command.go` and `_test.go` — parse and validate `assetRef` while retaining legacy Base64 input only for queued old commands.
- `internal/armada/message_sender.go` and `_test.go` — resolve prepared image assets and pass them to native clients.
- `internal/armada/message_executor.go` and `_test.go` — release only explicitly classified pre-send retryable failures.
- `internal/armada/message_state.go` and `_test.go` — CAS-delete the exact fresh `PROCESSING` claim.
- `internal/armada/start.go` and startup tests — build one process-global loader/cache from the existing Redis client and namespace.
- `internal/service/app/group.go` and tests — upload prepared main/thumbnail bytes without image conversion.
- `internal/service/node/node_processor.go` — carry a real thumbnail separately from upload bytes.
- `internal/service/node/message_payload.go` and `_test.go` — set `JPEGThumbnail` to the prepared small thumbnail.

Delete after the shared normalizer is in use:

- `internal/armada/card_thumbnail.go`.
- `internal/armada/card_thumbnail_test.go`.

No new Redis connection, Kafka topic, database schema, HTTP endpoint, or third-party module is required.

### Task 1: Parse and validate the image asset reference contract

**Files:**

- Modify: `internal/armada/message_command.go`
- Modify: `internal/armada/message_command_test.go`

- [ ] **Step 1: Write failing parser tests for new references**

Add a helper to `message_command_test.go`:

```go
func validImageAssetRef() *MessageImageAssetRef {
	return &MessageImageAssetRef{
		SHA256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		SizeBytes: 428312,
		Mimetype: "image/png",
		TransformProfile: "marketing-image-v1",
	}
}
```

Add table-driven cases that build an `IMAGE` payload with `Image: &MessageImage{AssetRef: validImageAssetRef()}` and assert successful parsing. Add invalid cases for:

```go
tests := []struct {
	name string
	mutate func(*MessageImageAssetRef)
}{
	{"short sha", func(ref *MessageImageAssetRef) { ref.SHA256 = "abc" }},
	{"non hex sha", func(ref *MessageImageAssetRef) { ref.SHA256 = strings.Repeat("z", 64) }},
	{"zero size", func(ref *MessageImageAssetRef) { ref.SizeBytes = 0 }},
	{"missing mimetype", func(ref *MessageImageAssetRef) { ref.Mimetype = " " }},
	{"unknown profile", func(ref *MessageImageAssetRef) { ref.TransformProfile = "marketing-image-v2" }},
}
```

Also add one test proving a media object with both `base64` and `assetRef` is rejected, and keep one existing valid Base64 test to preserve queued-command compatibility.

- [ ] **Step 2: Run the parser test and verify red**

Run:

```bash
go test ./internal/armada -run 'TestParseMessageCommand.*Image' -count=1
```

Expected: compilation fails because `MessageImageAssetRef` and `AssetRef` do not exist.

- [ ] **Step 3: Add the reference fields and strict validation**

Replace `MessageImage` with:

```go
type MessageImage struct {
	Base64   string                `json:"base64,omitempty"`
	Mimetype string                `json:"mimetype,omitempty"`
	AssetRef *MessageImageAssetRef `json:"assetRef,omitempty"`
}

type MessageImageAssetRef struct {
	SHA256           string `json:"sha256"`
	SizeBytes        int    `json:"sizeBytes"`
	Mimetype         string `json:"mimetype"`
	TransformProfile string `json:"transformProfile"`
}
```

Extend `MessageCommandPayload.trim()`:

```go
if p.Image != nil {
	trimMessageImage(p.Image)
}
if p.LinkCard != nil && p.LinkCard.Thumbnail != nil {
	trimMessageImage(p.LinkCard.Thumbnail)
}
if p.ButtonCard != nil && p.ButtonCard.Thumbnail != nil {
	trimMessageImage(p.ButtonCard.Thumbnail)
}
```

Add:

```go
func trimMessageImage(image *MessageImage) {
	image.Base64 = strings.TrimSpace(image.Base64)
	image.Mimetype = strings.TrimSpace(image.Mimetype)
	if image.AssetRef == nil {
		return
	}
	image.AssetRef.SHA256 = strings.TrimSpace(image.AssetRef.SHA256)
	image.AssetRef.Mimetype = strings.TrimSpace(image.AssetRef.Mimetype)
	image.AssetRef.TransformProfile = strings.TrimSpace(image.AssetRef.TransformProfile)
}
```

Replace `validateMessageImage` with exactly-one-source validation:

```go
func validateMessageImage(image *MessageImage, field string) error {
	if image == nil {
		return invalidCommand(field, "is required")
	}
	hasLegacy := image.Base64 != ""
	hasReference := image.AssetRef != nil
	if hasLegacy == hasReference {
		return invalidCommand(field, "must contain exactly one of base64 or assetRef")
	}
	if hasReference {
		return validateMessageImageAssetRef(image.AssetRef, field+".assetRef")
	}
	if image.Mimetype == "" {
		return invalidCommand(field+".mimetype", "is required")
	}
	if _, err := base64.StdEncoding.DecodeString(image.Base64); err != nil {
		return invalidCommand(field, "contains invalid base64")
	}
	return nil
}

func validateMessageImageAssetRef(ref *MessageImageAssetRef, field string) error {
	if len(ref.SHA256) != 64 {
		return invalidCommand(field+".sha256", "must be 64 lowercase hexadecimal characters")
	}
	if _, err := hex.DecodeString(ref.SHA256); err != nil {
		return invalidCommand(field+".sha256", "must be 64 lowercase hexadecimal characters")
	}
	if strings.ToLower(ref.SHA256) != ref.SHA256 {
		return invalidCommand(field+".sha256", "must be 64 lowercase hexadecimal characters")
	}
	if ref.SizeBytes <= 0 {
		return invalidCommand(field+".sizeBytes", "must be positive")
	}
	if ref.Mimetype == "" {
		return invalidCommand(field+".mimetype", "is required")
	}
	if ref.TransformProfile != "marketing-image-v1" {
		return invalidCommand(field+".transformProfile", "is unsupported")
	}
	return nil
}
```

Import `encoding/hex`. Keep `encoding/base64` only for the legacy compatibility branch.

- [ ] **Step 4: Run parser tests and verify green**

Run:

```bash
gofmt -w internal/armada/message_command.go internal/armada/message_command_test.go
go test ./internal/armada -run 'TestParseMessageCommand' -count=1
```

Expected: all parser tests pass; invalid references remain permanent validation failures and legacy Base64 remains accepted.

- [ ] **Step 5: Commit the contract parser**

```bash
git add internal/armada/message_command.go internal/armada/message_command_test.go
git commit -m "feat: parse Android image asset references"
```

### Task 2: Build the fixed 64MB sliding-expiry LRU

**Files:**

- Create: `internal/armada/image_asset_cache.go`
- Create: `internal/armada/image_asset_cache_test.go`

- [ ] **Step 1: Write failing capacity and access-renewal tests**

Create `image_asset_cache_test.go` with a mutable clock and small test-only capacity:

```go
package armada

import (
	"testing"
	"time"
)

func TestImageAssetCacheRenewsTwentyMinuteExpiryOnAccess(t *testing.T) {
	now := time.Date(2026, 7, 19, 10, 0, 0, 0, time.UTC)
	cache := newImageAssetCache(imageAssetCacheOptions{
		MaxCost: 64 << 20,
		TTL: 20 * time.Minute,
		Now: func() time.Time { return now },
	})
	key := imageAssetCacheKey{TenantID: 7, SHA256: strings.Repeat("a", 64), Profile: "marketing-image-v1"}
	value := cachedImageAsset{ImageBytes: []byte("image"), ThumbnailBytes: []byte("thumb")}
	cache.Set(key, value)

	now = now.Add(19 * time.Minute)
	if _, ok := cache.Get(key); !ok {
		t.Fatal("expected hit before first expiry")
	}
	now = now.Add(19 * time.Minute)
	if _, ok := cache.Get(key); !ok {
		t.Fatal("access must renew expiry by twenty minutes")
	}
	now = now.Add(20 * time.Minute)
	if _, ok := cache.Get(key); ok {
		t.Fatal("entry must expire after twenty inactive minutes")
	}
}

func TestImageAssetCacheEvictsLeastRecentlyUsedByBytes(t *testing.T) {
	cache := newImageAssetCache(imageAssetCacheOptions{
		MaxCost: 12,
		TTL: time.Hour,
		Now: time.Now,
	})
	first := imageAssetCacheKey{TenantID: 7, SHA256: strings.Repeat("1", 64), Profile: "marketing-image-v1"}
	second := imageAssetCacheKey{TenantID: 7, SHA256: strings.Repeat("2", 64), Profile: "marketing-image-v1"}
	third := imageAssetCacheKey{TenantID: 7, SHA256: strings.Repeat("3", 64), Profile: "marketing-image-v1"}
	cache.Set(first, cachedImageAsset{ImageBytes: make([]byte, 6)})
	cache.Set(second, cachedImageAsset{ImageBytes: make([]byte, 6)})
	_, _ = cache.Get(first)
	cache.Set(third, cachedImageAsset{ImageBytes: make([]byte, 6)})

	if _, ok := cache.Get(second); ok {
		t.Fatal("least recently used entry must be evicted")
	}
	if _, ok := cache.Get(first); !ok {
		t.Fatal("recently accessed entry must remain")
	}
}
```

Import `strings`. Add a third test that captures a returned value, evicts its cache entry, and proves the captured byte slice is still readable; this protects in-flight sends.

- [ ] **Step 2: Run the cache test and verify red**

Run:

```bash
go test ./internal/armada -run TestImageAssetCache -count=1
```

Expected: compilation fails because cache types do not exist.

- [ ] **Step 3: Implement the byte-weighted LRU core**

Create `image_asset_cache.go`:

```go
package armada

import (
	"container/list"
	"sync"
	"time"
)

const (
	imageAssetCacheMaxCost = int64(64 << 20)
	imageAssetCacheTTL = 20 * time.Minute
	imageAssetCacheCleanupInterval = time.Minute
)

type imageAssetCacheKey struct {
	TenantID int64
	SHA256 string
	Profile string
}

type cachedImageAsset struct {
	ImageBytes []byte
	ThumbnailBytes []byte
	Mimetype string
	ImageWidth int
	ImageHeight int
	ThumbnailWidth int
	ThumbnailHeight int
}

func (a cachedImageAsset) cost() int64 {
	return int64(len(a.ImageBytes) + len(a.ThumbnailBytes))
}

type imageAssetCacheOptions struct {
	MaxCost int64
	TTL time.Duration
	Now func() time.Time
}

type imageAssetCacheEntry struct {
	key imageAssetCacheKey
	value cachedImageAsset
	cost int64
	expiresAt time.Time
}

type imageAssetCacheStats struct {
	Entries int
	CostBytes int64
	Hits uint64
	Misses uint64
	Evictions uint64
	Expired uint64
}

type imageAssetCache struct {
	mu sync.Mutex
	items map[imageAssetCacheKey]*list.Element
	order *list.List
	maxCost int64
	ttl time.Duration
	now func() time.Time
	cost int64
	hits uint64
	misses uint64
	evictions uint64
	expired uint64
}

func newImageAssetCache(options imageAssetCacheOptions) *imageAssetCache {
	if options.MaxCost <= 0 {
		options.MaxCost = imageAssetCacheMaxCost
	}
	if options.TTL <= 0 {
		options.TTL = imageAssetCacheTTL
	}
	if options.Now == nil {
		options.Now = time.Now
	}
	return &imageAssetCache{
		items: make(map[imageAssetCacheKey]*list.Element),
		order: list.New(), maxCost: options.MaxCost, ttl: options.TTL, now: options.Now,
	}
}

func (c *imageAssetCache) Get(key imageAssetCacheKey) (cachedImageAsset, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	element, ok := c.items[key]
	if !ok {
		c.misses++
		return cachedImageAsset{}, false
	}
	entry := element.Value.(*imageAssetCacheEntry)
	if !c.now().Before(entry.expiresAt) {
		c.remove(element, false, true)
		c.misses++
		return cachedImageAsset{}, false
	}
	entry.expiresAt = c.now().Add(c.ttl)
	c.order.MoveToFront(element)
	c.hits++
	return entry.value, true
}

func (c *imageAssetCache) Set(key imageAssetCacheKey, value cachedImageAsset) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	cost := value.cost()
	if cost <= 0 || cost > c.maxCost {
		return false
	}
	if current, ok := c.items[key]; ok {
		c.remove(current, false, false)
	}
	entry := &imageAssetCacheEntry{
		key: key, value: value, cost: cost, expiresAt: c.now().Add(c.ttl),
	}
	element := c.order.PushFront(entry)
	c.items[key] = element
	c.cost += cost
	for c.cost > c.maxCost {
		c.remove(c.order.Back(), true, false)
	}
	return true
}

func (c *imageAssetCache) DeleteExpired() imageAssetCacheStats {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	for element := c.order.Back(); element != nil; {
		previous := element.Prev()
		entry := element.Value.(*imageAssetCacheEntry)
		if !now.Before(entry.expiresAt) {
			c.remove(element, false, true)
		}
		element = previous
	}
	return c.statsLocked()
}

func (c *imageAssetCache) Stats() imageAssetCacheStats {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.statsLocked()
}

func (c *imageAssetCache) remove(element *list.Element, eviction, expired bool) {
	if element == nil {
		return
	}
	entry := element.Value.(*imageAssetCacheEntry)
	delete(c.items, entry.key)
	c.order.Remove(element)
	c.cost -= entry.cost
	if eviction { c.evictions++ }
	if expired { c.expired++ }
}

func (c *imageAssetCache) statsLocked() imageAssetCacheStats {
	return imageAssetCacheStats{
		Entries: len(c.items), CostBytes: c.cost, Hits: c.hits, Misses: c.misses,
		Evictions: c.evictions, Expired: c.expired,
	}
}
```

The returned byte slices are immutable. Do not clone them in `Get`, because a per-send 500KB clone would defeat the reuse goal.

- [ ] **Step 4: Run unit and race tests**

Run:

```bash
gofmt -w internal/armada/image_asset_cache.go internal/armada/image_asset_cache_test.go
go test ./internal/armada -run TestImageAssetCache -count=1
go test -race ./internal/armada -run TestImageAssetCache -count=1
```

Expected: both commands pass; the race detector prints no race report.

- [ ] **Step 5: Commit the LRU unit**

```bash
git add internal/armada/image_asset_cache.go internal/armada/image_asset_cache_test.go
git commit -m "feat: add Android image process LRU"
```

### Task 3: Normalize source images and load them through Redis + singleflight

**Files:**

- Create: `internal/armada/image_asset_normalizer.go`
- Create: `internal/armada/image_asset_normalizer_test.go`
- Create: `internal/armada/image_asset_loader.go`
- Create: `internal/armada/image_asset_loader_test.go`

- [ ] **Step 1: Write failing normalizer tests**

Create `image_asset_normalizer_test.go`. Use an in-memory transparent PNG and assert the exact geometry:

```go
func TestNormalizeMarketingImageBuildsMainAndThumbnail(t *testing.T) {
	source := encodeTestPNG(t, 2000, 1000, color.NRGBA{R: 255, A: 128})
	asset, err := normalizeMarketingImage(source)
	if err != nil {
		t.Fatal(err)
	}
	if asset.Mimetype != "image/jpeg" || asset.ImageWidth != 1600 || asset.ImageHeight != 800 {
		t.Fatalf("main = %s %dx%d", asset.Mimetype, asset.ImageWidth, asset.ImageHeight)
	}
	if asset.ThumbnailWidth != 320 || asset.ThumbnailHeight != 160 {
		t.Fatalf("thumbnail = %dx%d", asset.ThumbnailWidth, asset.ThumbnailHeight)
	}
	if len(asset.ImageBytes) == 0 || len(asset.ThumbnailBytes) == 0 {
		t.Fatal("normalized JPEG outputs must be non-empty")
	}
}
```

Add focused tests for:

- a `320x180` source is not enlarged;
- `validateImagePixelCount(5000, 5001)` fails while `5000x5000` succeeds;
- invalid bytes return a decode error;
- a transparent pixel decodes from the output JPEG to a light/white-composited pixel rather than black;
- `validateNormalizedImageSize(5<<20)` succeeds and `validateNormalizedImageSize((5<<20)+1)` fails.

Define the shared PNG helper in the same test file:

```go
func encodeTestPNG(t *testing.T, width, height int, fill color.Color) []byte {
	t.Helper()
	canvas := image.NewNRGBA(image.Rect(0, 0, width, height))
	draw.Draw(canvas, canvas.Bounds(), &image.Uniform{C: fill}, image.Point{}, draw.Src)
	var output bytes.Buffer
	if err := png.Encode(&output, canvas); err != nil {
		t.Fatalf("encode test PNG: %v", err)
	}
	return output.Bytes()
}
```

Import `bytes`, `image`, `image/color`, `image/draw`, and `image/png` in `image_asset_normalizer_test.go`.

- [ ] **Step 2: Run the normalizer tests and verify red**

Run:

```bash
go test ./internal/armada -run 'TestNormalizeMarketingImage|TestValidateImage' -count=1
```

Expected: compilation fails because the normalizer functions do not exist.

- [ ] **Step 3: Implement `marketing-image-v1` exactly once**

Create `image_asset_normalizer.go`:

```go
package armada

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/gif"
	_ "image/png"

	"github.com/disintegration/imaging"
)

const (
	marketingImageMaxEdge = 1600
	marketingThumbnailMaxEdge = 320
	marketingImageJPEGQuality = 85
	marketingThumbnailJPEGQuality = 70
	marketingImageMaxPixels int64 = 25_000_000
	marketingNormalizedMaxBytes = 5 << 20
)

func normalizeMarketingImage(source []byte) (cachedImageAsset, error) {
	config, _, err := image.DecodeConfig(bytes.NewReader(source))
	if err != nil {
		return cachedImageAsset{}, fmt.Errorf("decode image config: %w", err)
	}
	if err := validateImagePixelCount(config.Width, config.Height); err != nil {
		return cachedImageAsset{}, err
	}
	decoded, err := imaging.Decode(bytes.NewReader(source), imaging.AutoOrientation(true))
	if err != nil {
		return cachedImageAsset{}, fmt.Errorf("decode marketing image: %w", err)
	}
	mainImage := fitWithoutUpscale(decoded, marketingImageMaxEdge)
	mainImage = compositeOnWhite(mainImage)
	thumbnail := fitWithoutUpscale(mainImage, marketingThumbnailMaxEdge)

	mainBytes, err := encodeJPEG(mainImage, marketingImageJPEGQuality)
	if err != nil {
		return cachedImageAsset{}, err
	}
	thumbnailBytes, err := encodeJPEG(thumbnail, marketingThumbnailJPEGQuality)
	if err != nil {
		return cachedImageAsset{}, err
	}
	if err := validateNormalizedImageSize(len(mainBytes) + len(thumbnailBytes)); err != nil {
		return cachedImageAsset{}, err
	}
	return cachedImageAsset{
		ImageBytes: mainBytes, ThumbnailBytes: thumbnailBytes, Mimetype: "image/jpeg",
		ImageWidth: mainImage.Bounds().Dx(), ImageHeight: mainImage.Bounds().Dy(),
		ThumbnailWidth: thumbnail.Bounds().Dx(), ThumbnailHeight: thumbnail.Bounds().Dy(),
	}, nil
}

func validateImagePixelCount(width, height int) error {
	if width <= 0 || height <= 0 || int64(width)*int64(height) > marketingImageMaxPixels {
		return fmt.Errorf("image dimensions exceed 25MP")
	}
	return nil
}

func validateNormalizedImageSize(size int) error {
	if size > marketingNormalizedMaxBytes {
		return fmt.Errorf("normalized image exceeds 5MB")
	}
	return nil
}

func fitWithoutUpscale(source image.Image, maxEdge int) image.Image {
	if source.Bounds().Dx() <= maxEdge && source.Bounds().Dy() <= maxEdge {
		return imaging.Clone(source)
	}
	return imaging.Fit(source, maxEdge, maxEdge, imaging.Lanczos)
}

func compositeOnWhite(source image.Image) image.Image {
	background := imaging.New(
		source.Bounds().Dx(), source.Bounds().Dy(), color.NRGBA{R: 255, G: 255, B: 255, A: 255})
	return imaging.Overlay(background, source, image.Point{}, 1)
}

func encodeJPEG(source image.Image, quality int) ([]byte, error) {
	var output bytes.Buffer
	if err := jpeg.Encode(&output, source, &jpeg.Options{Quality: quality}); err != nil {
		return nil, fmt.Errorf("encode marketing image as JPEG: %w", err)
	}
	return output.Bytes(), nil
}
```

Use `imaging.AutoOrientation(true)` as the existing repository does. Pixel validation must happen before the full decode.

- [ ] **Step 4: Run normalizer tests and verify green**

Run:

```bash
gofmt -w internal/armada/image_asset_normalizer.go internal/armada/image_asset_normalizer_test.go
go test ./internal/armada -run 'TestNormalizeMarketingImage|TestValidateImage' -count=1
```

Expected: all profile tests pass.

- [ ] **Step 5: Write failing Redis loader tests**

In `image_asset_loader_test.go`, create a counting Redis fake implementing only `Get(context.Context, string) *redis.StringCmd`. Add these cases:

```go
func TestImageAssetLoaderCachesAndSingleflightsConcurrentLoads(t *testing.T) {
	source := encodeTestPNG(t, 800, 400, color.NRGBA{R: 20, G: 40, B: 60, A: 255})
	ref := imageRefFor(source)
	redisClient := &countingImageRedis{value: source}
	loader := newImageAssetLoader(redisClient, "android-zhuan:", newImageAssetCache(imageAssetCacheOptions{}))

	const callers = 50
	var wait sync.WaitGroup
	wait.Add(callers)
	for range callers {
		go func() {
			defer wait.Done()
			if _, err := loader.Resolve(context.Background(), 7, &MessageImage{AssetRef: ref}); err != nil {
				t.Errorf("Resolve() error = %v", err)
			}
		}()
	}
	wait.Wait()
	if got := redisClient.calls.Load(); got != 1 {
		t.Fatalf("Redis GET calls = %d, want 1", got)
	}
}
```

Add tests asserting:

- Redis `redis.Nil` becomes non-retryable `IMAGE_ASSET_NOT_FOUND`;
- another Redis error is retryable;
- size mismatch and SHA mismatch become `IMAGE_ASSET_INVALID`;
- invalid image and over-25MP become `IMAGE_REENCODE_FAILED`;
- the physical key equals `android-zhuan:marketing:image:v1:7:<sha>`;
- a legacy Base64 media is normalized without Redis and remains accepted only for compatibility.

Define the reference and Redis fake used by those tests:

```go
type countingImageRedis struct {
	value []byte
	err error
	calls atomic.Int32
	lastKey atomic.Value
}

func (r *countingImageRedis) Get(
	_ context.Context,
	key string,
) *redis.StringCmd {
	r.calls.Add(1)
	r.lastKey.Store(key)
	if r.err != nil {
		return redis.NewStringResult("", r.err)
	}
	return redis.NewStringResult(string(r.value), nil)
}

func imageRefFor(source []byte) *MessageImageAssetRef {
	digest := sha256.Sum256(source)
	return &MessageImageAssetRef{
		SHA256: hex.EncodeToString(digest[:]),
		SizeBytes: len(source),
		Mimetype: "image/png",
		TransformProfile: "marketing-image-v1",
	}
}
```

Import `crypto/sha256`, `encoding/hex`, `sync/atomic`, and `github.com/redis/go-redis/v9` in `image_asset_loader_test.go`. The loader test may reuse `encodeTestPNG` because both test files use package `armada`.

- [ ] **Step 6: Run loader tests and verify red**

Run:

```bash
go test ./internal/armada -run TestImageAssetLoader -count=1
```

Expected: compilation fails because the loader and classified error do not exist.

- [ ] **Step 7: Implement classified loading, integrity checks, and singleflight**

Create `image_asset_loader.go` with this public-to-package boundary:

```go
package armada

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/redis/go-redis/v9"
	"golang.org/x/sync/singleflight"
)

type imageAssetRedis interface {
	Get(context.Context, string) *redis.StringCmd
}

type imageAssetFailure struct {
	Code string
	Message string
	Retryable bool
	err error
}

func (e *imageAssetFailure) Error() string { return e.Message + ": " + e.err.Error() }
func (e *imageAssetFailure) Unwrap() error { return e.err }

type messageImageResolver interface {
	Resolve(context.Context, int64, *MessageImage) (cachedImageAsset, error)
}

type imageAssetLoader struct {
	redis imageAssetRedis
	keyPrefix string
	cache *imageAssetCache
	flight singleflight.Group
}

func newImageAssetLoader(
	client imageAssetRedis,
	keyPrefix string,
	cache *imageAssetCache,
) *imageAssetLoader {
	return &imageAssetLoader{redis: client, keyPrefix: keyPrefix, cache: cache}
}

func (l *imageAssetLoader) Resolve(
	ctx context.Context,
	tenantID int64,
	image *MessageImage,
) (cachedImageAsset, error) {
	if image == nil {
		return cachedImageAsset{}, terminalImageFailure("IMAGE_ASSET_INVALID", "图片引用无效", errors.New("image is nil"))
	}
	if image.AssetRef == nil {
		legacy, err := base64.StdEncoding.DecodeString(image.Base64)
		if err != nil {
			return cachedImageAsset{}, terminalImageFailure("IMAGE_ASSET_INVALID", "图片内容无效", err)
		}
		asset, err := normalizeMarketingImage(legacy)
		if err != nil {
			return cachedImageAsset{}, terminalImageFailure("IMAGE_REENCODE_FAILED", "图片处理失败", err)
		}
		return asset, nil
	}
	ref := image.AssetRef
	cacheKey := imageAssetCacheKey{TenantID: tenantID, SHA256: ref.SHA256, Profile: ref.TransformProfile}
	if asset, ok := l.cache.Get(cacheKey); ok {
		return asset, nil
	}
	result := l.flight.DoChan(cacheIdentity(cacheKey), func() (any, error) {
		if asset, ok := l.cache.Get(cacheKey); ok {
			return asset, nil
		}
		return l.load(ctx, cacheKey, ref)
	})
	select {
	case <-ctx.Done():
		return cachedImageAsset{}, ctx.Err()
	case loaded := <-result:
		if loaded.Err != nil {
			return cachedImageAsset{}, loaded.Err
		}
		return loaded.Val.(cachedImageAsset), nil
	}
}

func (l *imageAssetLoader) load(
	ctx context.Context,
	cacheKey imageAssetCacheKey,
	ref *MessageImageAssetRef,
) (cachedImageAsset, error) {
	key := namespacedRedisKey(l.keyPrefix,
		"marketing:image:v1:"+strconv.FormatInt(cacheKey.TenantID, 10)+":"+ref.SHA256)
	raw, err := l.redis.Get(ctx, key).Bytes()
	if errors.Is(err, redis.Nil) {
		return cachedImageAsset{}, terminalImageFailure("IMAGE_ASSET_NOT_FOUND", "图片缓存不存在", err)
	}
	if err != nil {
		return cachedImageAsset{}, &imageAssetFailure{
			Code: "", Message: "读取图片缓存失败", Retryable: true, err: err,
		}
	}
	if len(raw) != ref.SizeBytes {
		return cachedImageAsset{}, terminalImageFailure("IMAGE_ASSET_INVALID", "图片缓存校验失败", errors.New("size mismatch"))
	}
	digest := sha256.Sum256(raw)
	if hex.EncodeToString(digest[:]) != ref.SHA256 {
		return cachedImageAsset{}, terminalImageFailure("IMAGE_ASSET_INVALID", "图片缓存校验失败", errors.New("sha mismatch"))
	}
	asset, err := normalizeMarketingImage(raw)
	if err != nil {
		return cachedImageAsset{}, terminalImageFailure("IMAGE_REENCODE_FAILED", "图片处理失败", err)
	}
	l.cache.Set(cacheKey, asset)
	return asset, nil
}

func terminalImageFailure(code, message string, err error) error {
	return &imageAssetFailure{Code: code, Message: message, err: err}
}

func cacheIdentity(key imageAssetCacheKey) string {
	return strconv.FormatInt(key.TenantID, 10) + ":" + key.SHA256 + ":" + key.Profile
}
```

Remove unused `fmt` and `strings` imports if the final code does not use them. Do not call `EXPIRE` from Android.

Instrument only cache misses. Around Redis GET and `normalizeMarketingImage`, capture `time.Now()` durations and emit one debug record after success:

```go
zap.L().Debug("Android image asset loaded",
	zap.Int64("tenantId", cacheKey.TenantID),
	zap.String("shaPrefix", ref.SHA256[:8]),
	zap.Int("sourceBytes", len(raw)),
	zap.Int("imageBytes", len(asset.ImageBytes)),
	zap.Int("thumbnailBytes", len(asset.ThumbnailBytes)),
	zap.Duration("redisGetDuration", redisDuration),
	zap.Duration("normalizeDuration", normalizeDuration))
```

For failures, log only `retryable`, the stable error code (or `REDIS_FETCH_FAILED`), tenant ID, SHA prefix, and elapsed duration. Do not log the physical Key, full SHA, image bytes, phone, or group.

- [ ] **Step 8: Run loader tests including race detection**

Run:

```bash
gofmt -w internal/armada/image_asset_loader.go internal/armada/image_asset_loader_test.go
go test ./internal/armada -run 'TestImageAssetLoader|TestNormalizeMarketingImage|TestImageAssetCache' -count=1
go test -race ./internal/armada -run 'TestImageAssetLoaderCachesAndSingleflights|TestImageAssetCache' -count=1
```

Expected: all tests pass; the concurrent loader records exactly one Redis GET and the race detector reports no race.

- [ ] **Step 9: Commit the resolver unit**

```bash
git add internal/armada/image_asset_normalizer.go \
  internal/armada/image_asset_normalizer_test.go \
  internal/armada/image_asset_loader.go \
  internal/armada/image_asset_loader_test.go
git commit -m "feat: resolve and normalize Android image assets"
```

### Task 4: Pass prepared bytes through the sender and native image/card APIs

**Files:**

- Modify: `internal/armada/message_sender.go`
- Modify: `internal/armada/message_sender_test.go`
- Delete: `internal/armada/card_thumbnail.go`
- Delete: `internal/armada/card_thumbnail_test.go`
- Modify: `internal/service/app/group.go`
- Modify: `internal/service/node/node_processor.go`
- Modify: `internal/service/node/message_payload.go`
- Modify: `internal/service/node/message_payload_test.go`

- [ ] **Step 1: Write failing sender tests for prepared asset reuse**

Extend the sender test fake with `imageAsset cachedImageAsset` and `thumbnailAsset *cachedImageAsset`. Change its interface methods to accept prepared assets:

```go
func (f *fakeZhuanMessageClient) SendImage(
	group jabber.JID,
	text string,
	asset cachedImageAsset,
	mentions []string,
	mentionAll bool,
) (string, error) {
	f.record("image", group, text, mentions, mentionAll)
	f.imageAsset = asset
	return f.messageID, f.sendErr
}
```

Add a resolver fake:

```go
type recordingImageResolver struct {
	asset cachedImageAsset
	err error
	calls int
}

func (r *recordingImageResolver) Resolve(
	context.Context,
	int64,
	*MessageImage,
) (cachedImageAsset, error) {
	r.calls++
	return r.asset, r.err
}
```

Update the existing five-message dispatch test so the `IMAGE` case provides `AssetRef`, injects the resolver into `ZhuanMessageSender.Images`, and asserts the exact `ImageBytes`, `ThumbnailBytes`, and `image/jpeg` value received by the fake client.

Add card cases proving:

- link/button with a thumbnail call `Resolve` once and receive the prepared thumbnail;
- card without a thumbnail does not call `Resolve`;
- `IMAGE_ASSET_NOT_FOUND`, `IMAGE_ASSET_INVALID`, and `IMAGE_REENCODE_FAILED` return terminal `MessageSendResult` values without calling the native client;
- a retryable `imageAssetFailure` returns an error that satisfies `ErrMessagePreSendRetryable` and does not call the native client.

- [ ] **Step 2: Change the protobuf test to require a small thumbnail argument**

Change `TestBuildImageGroupPayloadMarksMentionAllWithoutMemberJIDs` to pass distinct bytes:

```go
payload, err := BuildImageGroupPayload(
	"caption", "image/jpeg", []byte("small-thumbnail"),
	nil, true, mediaInfo,
)
```

Assert:

```go
if got := string(payload.GetImageMessage().GetJPEGThumbnail()); got != "small-thumbnail" {
	t.Fatalf("JPEGThumbnail = %q", got)
}
```

- [ ] **Step 3: Run sender and node tests and verify red**

Run:

```bash
go test ./internal/armada ./internal/service/node -run 'TestZhuanMessageSender|TestBuildImageGroupPayload' -count=1
```

Expected: compilation fails because sender/client signatures and prepared-thumbnail plumbing still use raw/Base64 media.

- [ ] **Step 4: Resolve image-bearing commands before native calls**

Add to `ZhuanMessageSender`:

```go
var ErrMessagePreSendRetryable = errors.New("Armada message failed before WhatsApp send and may retry")

type ZhuanMessageSender struct {
	Resolver ZhuanMessageClientResolver
	Images messageImageResolver
	States MessageSendabilityStateStore
	Now func() time.Time
}
```

Change the client interface to:

```go
type ZhuanMessageClient interface {
	SendText(jabber.JID, string, []string, bool) (string, error)
	SendImage(jabber.JID, string, cachedImageAsset, []string, bool) (string, error)
	SendLinkCard(jabber.JID, string, MessageLinkCard, *cachedImageAsset, []string, bool) (string, error)
	SendButtonCard(jabber.JID, string, MessageButtonCard, *cachedImageAsset, []string, bool) (string, error)
	ResolveGroupSendability(context.Context, jabber.JID, time.Time) (GroupSendabilitySnapshot, error)
}
```

Add sender helpers:

```go
func (s *ZhuanMessageSender) resolveImage(
	ctx context.Context,
	tenantID int64,
	image *MessageImage,
) (cachedImageAsset, *MessageSendResult, error) {
	if s.Images == nil {
		result := failedMessageResult("SENDER_UNAVAILABLE", "图片加载器不可用")
		return cachedImageAsset{}, &result, nil
	}
	asset, err := s.Images.Resolve(ctx, tenantID, image)
	if err == nil {
		return asset, nil, nil
	}
	var failure *imageAssetFailure
	if errors.As(err, &failure) && failure.Retryable {
		return cachedImageAsset{}, nil, fmt.Errorf("%w: %v", ErrMessagePreSendRetryable, err)
	}
	if errors.As(err, &failure) {
		result := failedMessageResult(failure.Code, failure.Message)
		return cachedImageAsset{}, &result, nil
	}
	return cachedImageAsset{}, nil, err
}

func (s *ZhuanMessageSender) resolveOptionalImage(
	ctx context.Context,
	tenantID int64,
	image *MessageImage,
) (*cachedImageAsset, *MessageSendResult, error) {
	if image == nil {
		return nil, nil, nil
	}
	asset, failed, err := s.resolveImage(ctx, tenantID, image)
	if failed != nil || err != nil {
		return nil, failed, err
	}
	return &asset, nil, nil
}
```

In the `IMAGE`, `LINK_CARD`, and `BUTTON_CARD` switch branches, resolve before calling the client. For example:

```go
case "IMAGE":
	asset, failed, loadErr := s.resolveImage(ctx, command.Payload.TenantID, command.Payload.Image)
	if loadErr != nil {
		return MessageSendResult{}, loadErr
	}
	if failed != nil {
		return *failed, nil
	}
	messageID, err = client.SendImage(group, text, asset, nil, command.Payload.MentionAll)
```

Card branches call `resolveOptionalImage` on their thumbnail and pass the resulting pointer separately. Remove Base64 decoding from `ZhuanMessageSender.Send`.

- [ ] **Step 5: Upload prepared main/thumbnail bytes without re-encoding**

Change `waAppMessageClient.SendImage`:

```go
func (c *waAppMessageClient) SendImage(
	group jabber.JID,
	text string,
	asset cachedImageAsset,
	mentions []string,
	mentionAll bool,
) (string, error) {
	message, err := c.waApp.SendGroupImageMessage(
		group, text, asset.ImageBytes, asset.ThumbnailBytes,
		asset.Mimetype, mentions, mentionAll,
	)
	return nativeMessageID(message), err
}
```

Replace `uploadOptionalThumbnail` with an upload-only method:

```go
func (c *waAppMessageClient) uploadPreparedThumbnail(
	asset *cachedImageAsset,
	mediaType media.MediaType,
) (*entity.MediaDownloadInfo, error) {
	if asset == nil {
		return nil, nil
	}
	mediaConn, err := c.waApp.QueryMediaConn()
	if err != nil {
		return nil, err
	}
	return media.UploadFor(
		c.waApp.GetNetWorkProxy(),
		io.NopCloser(bytes.NewReader(asset.ThumbnailBytes)),
		mediaType,
		mediaConn.HostName(),
		mediaConn.Auth(),
	)
}
```

Measure each `media.UploadFor` call and emit a debug log with `mediaType`, byte count, and elapsed duration. Do not include the upload URL, token, media key, SHA, task, group, or account.

For link/button cards, set `HyperLinkMessage.Thumbnail` to `asset.ThumbnailBytes`, dimensions to `ThumbnailWidth/ThumbnailHeight`, and `MediaInfo` to the upload result. There must be no call to `normalizeCardThumbnail`, `jpeg.Encode`, `imaging.Resize`, or Base64 decode in the per-card send path.

- [ ] **Step 6: Carry distinct upload and thumbnail bytes through WaApp/node**

Change `WaApp.SendGroupImageMessage` signature to:

```go
func (w *WaApp) SendGroupImageMessage(
	groupJID jabber.JID,
	text string,
	fileBytes []byte,
	thumbnailBytes []byte,
	mimetype string,
	mentionedUsers []string,
	mentionAll bool,
) (*msg.MySendMsg, error)
```

Upload only `fileBytes`; pass `thumbnailBytes` to `MainNodeProcessor.SendImageGroupMessage`. Change that method and its call to `BuildImageGroupPayload` so the argument named `thumbnailBytes` is never reused as upload input:

```go
w2, err := BuildImageGroupPayload(
	text, mimetype, thumbnailBytes, mentionedUsers, mentionAll, info,
)
```

Rename the third parameter of `BuildImageGroupPayload` from `fileBytes` to `thumbnailBytes` and assign:

```go
JPEGThumbnail: thumbnailBytes,
```

Around `waitServerAck` in the image and link/card native paths, emit a debug duration with only message family and success/failure class. This provides the agreed upload/ACK split without high-cardinality metric labels or sensitive identifiers.

- [ ] **Step 7: Remove the obsolete card-specific normalizer**

After all references have moved to `normalizeMarketingImage`, delete `card_thumbnail.go` and `card_thumbnail_test.go`. Confirm with:

```bash
rg -n 'normalizeCardThumbnail|linkCardThumbnailProfile|buttonCardThumbnailProfile' internal
```

Expected: no matches.

- [ ] **Step 8: Run focused sender/native tests**

Run:

```bash
gofmt -w internal/armada/message_sender.go internal/armada/message_sender_test.go \
  internal/service/app/group.go internal/service/node/node_processor.go \
  internal/service/node/message_payload.go internal/service/node/message_payload_test.go
go test ./internal/armada ./internal/service/app ./internal/service/node -run 'TestZhuanMessageSender|TestBuildImageGroupPayload|TestBuildLinkCardGroupPayload|TestBuildButtonCardGroupPayload' -count=1
```

Expected: prepared main and thumbnail bytes reach the correct native fields; no focused test performs per-message image conversion.

- [ ] **Step 9: Commit prepared-byte sending**

```bash
git add internal/armada/message_sender.go internal/armada/message_sender_test.go \
  internal/armada/card_thumbnail.go internal/armada/card_thumbnail_test.go \
  internal/service/app/group.go internal/service/node/node_processor.go \
  internal/service/node/message_payload.go internal/service/node/message_payload_test.go
git commit -m "feat: reuse prepared Android image bytes"
```

### Task 5: Safely release only pre-send retryable command claims

**Files:**

- Modify: `internal/armada/message_executor.go`
- Modify: `internal/armada/message_executor_test.go`
- Modify: `internal/armada/message_state.go`
- Modify: `internal/armada/message_state_test.go`

- [ ] **Step 1: Write a failing executor ordering test**

Extend `recordingMessageStateStore` with `releaseErr error` and this method:

```go
func (s *recordingMessageStateStore) ReleaseBeforeSend(
	context.Context,
	string,
	time.Time,
	string,
) error {
	s.operations = append(s.operations, "release")
	return s.releaseErr
}
```

Add:

```go
func TestMessageCommandExecutorReleasesOnlyPreSendRetryableFailure(t *testing.T) {
	operations := []string{}
	states := &recordingMessageStateStore{
		operations: operations,
		claim: MessageCommandClaim{Phase: MessagePhaseProcessing, New: true},
	}
	sender := &recordingCommandSender{
		operations: &states.operations,
		err: fmt.Errorf("%w: redis timeout", ErrMessagePreSendRetryable),
	}
	events := &recordingMessageEventWriter{operations: &states.operations}
	executor := &MessageCommandExecutor{
		States: states, Sender: sender, Events: events, WorkerID: "worker-1", Now: time.Now,
	}

	err := executor.Execute(context.Background(), executorMessageCommand())
	if !errors.Is(err, ErrMessagePreSendRetryable) {
		t.Fatalf("error = %v", err)
	}
	if want := []string{"claim", "send", "release"}; !reflect.DeepEqual(states.operations, want) {
		t.Fatalf("operations = %#v, want %#v", states.operations, want)
	}
}
```

Add another subtest where sender returns a normal context/network error and assert operations remain `claim,send` with no release. This prevents accidental release after an ambiguous WhatsApp attempt.

- [ ] **Step 2: Write failing Redis CAS-release tests**

In `message_state_test.go`, use miniredis and add:

```go
func TestRedisMessageCommandStateStoreReleasesExactFreshClaim(t *testing.T) {
	server := miniredis.RunT(t)
	client := redis.NewClient(&redis.Options{Addr: server.Addr()})
	store := NewRedisMessageCommandStateStore(client, "android:", time.Hour, time.Minute)
	claimedAt := time.Date(2026, 7, 19, 10, 0, 0, 0, time.UTC)

	claim, err := store.Claim(context.Background(), "cmd-1", claimedAt, "worker-1")
	if err != nil || !claim.New {
		t.Fatalf("Claim() = %#v, %v", claim, err)
	}
	if err := store.ReleaseBeforeSend(
		context.Background(), "cmd-1", claimedAt, "worker-1"); err != nil {
		t.Fatal(err)
	}
	if server.Exists("android:" + messageCommandStateKeyPrefix + "cmd-1") {
		t.Fatal("exact fresh PROCESSING claim must be deleted")
	}
}
```

Add cases proving a different worker, different `claimedAt`, `RESULT_STORED`, and `PUBLISHED` state are never deleted and return `ErrMessageCommandClaimChanged`.

- [ ] **Step 3: Run executor/state tests and verify red**

Run:

```bash
go test ./internal/armada -run 'TestMessageCommandExecutorReleases|TestRedisMessageCommandStateStoreReleases' -count=1
```

Expected: compilation fails because the state interface and Redis store have no release operation.

- [ ] **Step 4: Extend the state interface with exact-claim release**

In `message_state.go`, add:

```go
var ErrMessageCommandClaimChanged = errors.New("Armada message command claim changed")

type MessageCommandStateStore interface {
	Claim(context.Context, string, time.Time, string) (MessageCommandClaim, error)
	ReleaseBeforeSend(context.Context, string, time.Time, string) error
	StoreResult(context.Context, string, MessageSendResult, time.Time, string) error
	MarkPublished(context.Context, string, time.Time) error
}
```

Add a compare-and-delete script:

```go
var messageStateReleaseBeforeSend = redis.NewScript(`
local current = redis.call('GET', KEYS[1])
if current ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[1])
return 1
`)
```

Implement the method without a broad `DEL`:

```go
func (s *RedisMessageCommandStateStore) ReleaseBeforeSend(
	ctx context.Context,
	commandID string,
	claimedAt time.Time,
	workerID string,
) error {
	key := namespacedRedisKey(s.keyPrefix,
		messageCommandStateKeyPrefix+strings.TrimSpace(commandID))
	expected := StoredMessageCommandState{
		Phase: MessagePhaseProcessing,
		UpdatedAt: claimedAt.UnixMilli(),
		WorkerID: normalizeMessageWorkerID(workerID),
	}
	payload, err := json.Marshal(expected)
	if err != nil {
		return fmt.Errorf("encode Armada message claim release: %w", err)
	}
	released, err := messageStateReleaseBeforeSend.Run(
		ctx, s.client, []string{key}, string(payload),
	).Int()
	if err != nil {
		return fmt.Errorf("release Armada message command before send: %w", err)
	}
	if released != 1 {
		return ErrMessageCommandClaimChanged
	}
	return nil
}
```

This operation is legal only before calling any `ZhuanMessageClient` send method.

- [ ] **Step 5: Make the executor release only the sentinel error**

In `MessageCommandExecutor.execute`, retain `claimedAt` and add this branch immediately after `Sender.Send` returns:

```go
current, err = e.Sender.Send(ctx, command)
if err != nil {
	wrapped := fmt.Errorf("send Armada message command: %w", err)
	if errors.Is(err, ErrMessagePreSendRetryable) {
		releaseErr := e.States.ReleaseBeforeSend(
			ctx, command.CommandID, claimedAt, workerID,
		)
		if releaseErr != nil {
			return errors.Join(wrapped,
				fmt.Errorf("release Armada message command before send: %w", releaseErr))
		}
	}
	return wrapped
}
```

Do not call release for context cancellation, ACK errors, upload failures, general sender errors, `StoreResult` errors, or event-publication errors.

- [ ] **Step 6: Update all state-store fakes and run tests**

Add a no-op or recording `ReleaseBeforeSend` method to every `MessageCommandStateStore` test fake. Then run:

```bash
gofmt -w internal/armada/message_executor.go internal/armada/message_executor_test.go \
  internal/armada/message_state.go internal/armada/message_state_test.go
go test ./internal/armada -run 'TestMessageCommandExecutor|TestRedisMessageCommandStateStore' -count=1
```

Expected: pre-send transient errors order as `claim,send,release`; all existing `PROCESSING -> RESULT_STORED -> PUBLISHED` and stale-unknown tests remain green.

- [ ] **Step 7: Commit the pre-send retry boundary**

```bash
git add internal/armada/message_executor.go internal/armada/message_executor_test.go \
  internal/armada/message_state.go internal/armada/message_state_test.go
git commit -m "fix: retry image fetch before WhatsApp send"
```

### Task 6: Wire one process-global loader and add bounded observability

**Files:**

- Modify: `internal/armada/start.go`
- Modify: `internal/armada/start_test.go`
- Modify: `internal/armada/image_asset_cache.go`
- Modify: `internal/armada/image_asset_cache_test.go`

- [ ] **Step 1: Write a failing cleanup-runtime test**

Add to `image_asset_cache_test.go`:

```go
func TestImageAssetCacheMaintenanceStopsWithContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cache := newImageAssetCache(imageAssetCacheOptions{})
	done := startImageAssetCacheMaintenance(ctx, cache, time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("image cache maintenance did not stop")
	}
}
```

- [ ] **Step 2: Implement periodic cleanup and low-cardinality stats logging**

Add to `image_asset_cache.go`:

```go
func startImageAssetCacheMaintenance(
	ctx context.Context,
	cache *imageAssetCache,
	interval time.Duration,
) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				stats := cache.DeleteExpired()
				zap.L().Info("Android image cache stats",
					zap.Int("entries", stats.Entries),
					zap.Int64("costBytes", stats.CostBytes),
					zap.Uint64("hits", stats.Hits),
					zap.Uint64("misses", stats.Misses),
					zap.Uint64("evictions", stats.Evictions),
					zap.Uint64("expired", stats.Expired))
			}
		}
	}()
	return done
}
```

Import `context` and `go.uber.org/zap`. The once-per-minute log must not include tenant, SHA, group, phone, or command ID.

- [ ] **Step 3: Build the loader once during `Start`**

Immediately after `redisPrefix := db.KeyPrefix()` in `Start`, construct:

```go
imageCache := newImageAssetCache(imageAssetCacheOptions{
	MaxCost: imageAssetCacheMaxCost,
	TTL: imageAssetCacheTTL,
	Now: time.Now,
})
imageLoader := newImageAssetLoader(redisClient, redisPrefix, imageCache)
```

After constructing `messageSender`, inject:

```go
messageSender.Images = imageLoader
```

Start maintenance after configuration and Redis stream validation have succeeded, immediately before `startCommandPools`:

```go
imageCacheDone := startImageAssetCacheMaintenance(
	adapterContext, imageCache, imageAssetCacheCleanupInterval,
)
```

On the `startCommandPools` failure path, call `cancelAdapter()` and wait for `<-imageCacheDone` before returning. In the normal `StopFunc`, cancel first, then wait with the provided stop context:

```go
var imageCacheErr error
select {
case <-imageCacheDone:
case <-stopContext.Done():
	imageCacheErr = stopContext.Err()
}
stopErr = errors.Join(
	consumerErr, dispatcherErr, schedulerErr, imageCacheErr,
	closeGroupJoinEvents(), closeMessageEvents(), kafkaEventWriter.Close(),
)
```

Do not create one cache per consumer, account, task, or group.

- [ ] **Step 4: Add startup assertions**

In `start_test.go`, preserve existing disabled and failure-cleanup tests. Add a test around `startImageAssetCacheMaintenance` or the available Start factory seam that asserts:

- one cache runtime is started regardless of message consumer concurrency;
- cancellation closes it;
- a startup failure after it starts also closes it.

Use channels and `time.Second` test deadlines; do not use sleep-based assertions.

- [ ] **Step 5: Run focused startup and cache tests**

Run:

```bash
gofmt -w internal/armada/start.go internal/armada/start_test.go \
  internal/armada/image_asset_cache.go internal/armada/image_asset_cache_test.go
go test ./internal/armada -run 'TestImageAssetCache|TestStart|TestArmada' -count=1
go test -race ./internal/armada -run 'TestImageAssetCache|TestImageAssetLoaderCachesAndSingleflights' -count=1
```

Expected: tests pass, cleanup exits on cancellation, and the race detector reports no race.

- [ ] **Step 6: Commit startup wiring**

```bash
git add internal/armada/start.go internal/armada/start_test.go \
  internal/armada/image_asset_cache.go internal/armada/image_asset_cache_test.go
git commit -m "feat: wire global Android image cache"
```

### Task 7: Run the Android quality gate

**Files:**

- No new files; verification may require small test-only fixes in files already listed above.

- [ ] **Step 1: Format every changed Go file**

Run:

```bash
gofmt -w internal/armada/image_asset_cache.go internal/armada/image_asset_cache_test.go \
  internal/armada/image_asset_normalizer.go internal/armada/image_asset_normalizer_test.go \
  internal/armada/image_asset_loader.go internal/armada/image_asset_loader_test.go \
  internal/armada/message_command.go internal/armada/message_command_test.go \
  internal/armada/message_sender.go internal/armada/message_sender_test.go \
  internal/armada/message_executor.go internal/armada/message_executor_test.go \
  internal/armada/message_state.go internal/armada/message_state_test.go \
  internal/armada/start.go internal/armada/start_test.go \
  internal/service/app/group.go internal/service/node/node_processor.go \
  internal/service/node/message_payload.go internal/service/node/message_payload_test.go
```

Expected: `gofmt -d` on the same existing paths prints no diff. Omit deleted card-thumbnail paths from the final command.

- [ ] **Step 2: Run static analysis and build**

Run from the Zhuan repository root:

```bash
go vet ./...
go build ./...
```

Expected: both commands exit 0 with no compile or vet errors.

- [ ] **Step 3: Run all tests**

Run:

```bash
go test ./...
```

Expected: all packages pass with zero failures.

- [ ] **Step 4: Run the concurrency gate**

Run:

```bash
go test -race ./internal/armada -run 'TestImageAssetCache|TestImageAssetLoader|TestMessageCommandExecutorReleases' -count=1
```

Expected: all selected tests pass and the race detector emits no warning.

- [ ] **Step 5: Check for forbidden old hot-path operations**

Run:

```bash
rg -n 'DecodeString\(command\.Payload\.Image\.Base64\)|normalizeCardThumbnail|JPEGThumbnail:\s*fileBytes' internal
git diff --check
```

Expected: `rg` returns no matches and `git diff --check` prints no errors. A Base64 compatibility decode is allowed only inside `image_asset_loader.go` when `AssetRef == nil`.

- [ ] **Step 6: Commit any verification-only corrections**

If formatting or tests required corrections, commit only those corrections:

```bash
git add internal/armada internal/service/app/group.go \
  internal/service/node/node_processor.go internal/service/node/message_payload.go \
  internal/service/node/message_payload_test.go
git commit -m "test: complete Android image cache verification"
```

If no files changed after the quality gate, do not create an empty commit.

### Task 8: Perform the confirmed test-environment acceptance

**Files:**

- Modify after evidence exists: `../armada/.harness/changes/2026-07-19-android-marketing-image-redis-lru.md`

- [ ] **Step 1: Stop for environment confirmation before remote work**

Ask the user to confirm the exact test environment, Armada instance, Android Zhuan instance, shared Redis endpoint/namespace, and whether deployment is authorized. Do not SSH, deploy, change a security group, or inspect remote credentials before this confirmation.

- [ ] **Step 2: Verify the namespace contract without printing secrets**

After confirmation, record these non-secret facts only:

```text
Armada ANDROID_IMAGE_REDIS_KEY_PREFIX
Android [redis].keyprefix
Redis mode (standalone/cluster)
TLS enabled/disabled
logical database number
```

Expected: both prefixes are identical; cluster uses database 0; neither log nor change record contains username/password or full connection strings.

- [ ] **Step 3: Deploy both verified commits using the environment's existing test workflow**

Use the repository deployment procedures already approved for the confirmed environment. Do not run `docker compose down -v`, delete Redis data, clear Kafka topics, or modify MySQL image rows. Record both commit IDs and container/image versions.

- [ ] **Step 4: Send one template image to at least 100 groups**

Use one Android marketing task whose targets share the same tenant and template image. Record task ID, target count, start/end time, success/failure counts, Kafka lag, and Android process count; do not record message text, full group JIDs, phone numbers, or image content.

- [ ] **Step 5: Verify Redis contains one source asset**

Using authenticated Redis tooling inside the approved environment, scan only the agreed namespace:

```bash
redis-cli --scan --pattern "${ANDROID_IMAGE_REDIS_KEY_PREFIX}marketing:image:v1:${TEST_TENANT_ID}:*"
```

For the one expected Key, inspect `PTTL`, `STRLEN`, and `MEMORY USAGE`. Expected:

- exactly one source-image Key for the tested SHA;
- `STRLEN <= 512000` because upload ingestion already enforced the limit;
- TTL is positive and near 24 hours after Armada enqueue;
- repeating Android reads does not increase TTL;
- starting a new Armada task with the same image refreshes TTL.

Set `ANDROID_IMAGE_REDIS_KEY_PREFIX` and `TEST_TENANT_ID` in the approved shell without echoing credentials; do not paste credentials or the full physical Key into the change record.

- [ ] **Step 6: Verify Kafka/outbox and Android cache behavior**

Inspect a bounded sample of the confirmed task's new command payloads and Android logs/metrics. Expected:

```text
100 commands: same assetRef.sha256
100 commands: no image.base64 / thumbnail.base64
per Android process: one Redis GET and one normalization for the tested SHA
subsequent commands: LRU hits
each group: independent media upload and ACK
```

If more than one Android process consumes the task, one GET/normalization per process is correct.

- [ ] **Step 7: Verify 20-minute access expiry and 64MB eviction with controlled tests**

In the confirmed test environment, first keep accessing the same image inside 20 minutes and verify it remains a hit. Then use a controlled idle period exceeding 20 minutes and verify the next command performs one new Redis GET/normalization. Exercise enough distinct prepared images to exceed 64MB and verify the oldest cold entry is evicted without interrupting in-flight sends.

- [ ] **Step 8: Record evidence and close the change record**

Append exact test commands, sanitized outputs, commit IDs, task result counts, Redis Key count/TTL/size, LRU hit/miss/eviction evidence, and any remaining limitations to the Armada change record. Mark it complete only if both repository quality gates and all acceptance assertions passed.

Commit the evidence document in Armada:

```bash
git add .harness/changes/2026-07-19-android-marketing-image-redis-lru.md
git commit -m "docs: record Android image cache acceptance"
```
