package com.armada.account.model.vo;

/**
 * 批量账号预估 SQL 聚合行。
 *
 * <p>各种跳过数量采用互斥口径，合计值可以直接从 matched 中扣除。</p>
 */
public class AccountBatchPreviewRow {

    private long matched;
    private long banned;
    private long unbound;
    private long takingOver;
    private long alreadyPending;
    private long alreadyOnline;
    private long missingCredential;

    public long getMatched() {
        return matched;
    }

    public void setMatched(long matched) {
        this.matched = matched;
    }

    public long getBanned() {
        return banned;
    }

    public void setBanned(long banned) {
        this.banned = banned;
    }

    public long getUnbound() {
        return unbound;
    }

    public void setUnbound(long unbound) {
        this.unbound = unbound;
    }

    public long getTakingOver() {
        return takingOver;
    }

    public void setTakingOver(long takingOver) {
        this.takingOver = takingOver;
    }

    public long getAlreadyPending() {
        return alreadyPending;
    }

    public void setAlreadyPending(long alreadyPending) {
        this.alreadyPending = alreadyPending;
    }

    public long getAlreadyOnline() {
        return alreadyOnline;
    }

    public void setAlreadyOnline(long alreadyOnline) {
        this.alreadyOnline = alreadyOnline;
    }

    public long getMissingCredential() {
        return missingCredential;
    }

    public void setMissingCredential(long missingCredential) {
        this.missingCredential = missingCredential;
    }
}
