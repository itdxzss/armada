package com.armada.group.model;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class GroupLinkImportResultTest {
    @Test void codeRoundTrip() {
        assertEquals(2, GroupLinkImportResult.EXISTS.code());
        assertEquals(GroupLinkImportResult.DUPLICATE, GroupLinkImportResult.fromCode(3));
        assertEquals("已存在", GroupLinkImportResult.EXISTS.label());
    }
    @Test void fromCodeRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> GroupLinkImportResult.fromCode(9));
    }
}
