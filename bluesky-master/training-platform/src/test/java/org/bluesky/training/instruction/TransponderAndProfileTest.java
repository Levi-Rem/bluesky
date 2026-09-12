package org.bluesky.training.instruction;

import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P14：应答机/速度恢复/特情 profile（详细设计 2.2 §7.6/§7.7/§7.8）。 */
class TransponderAndProfileTest {

    // ---------------------------------------------------------------- SQK

    @Test
    void givenSquawkWhenParsedThenLeadingZerosKeptAnd0000Rejected() {
        Map<String, Object> set = TransponderCommandParsers.parseSquawk("SQK 0042");
        assertEquals("SET", set.get("action"));
        assertEquals("0042", set.get("squawk"), "前导零必须以字符串保留");
        assertEquals(Boolean.FALSE, set.get("adapterRequired"), "SQK 不发送 Adapter");

        Map<String, Object> clear = TransponderCommandParsers.parseSquawk("SQK CLR");
        assertEquals("CLEAR", clear.get("action"));
        assertNull(clear.get("squawk"), "CLR 置无效");

        // 8/9 非八进制、长度、0000
        V2DomainException eight = assertThrows(V2DomainException.class,
                () -> TransponderCommandParsers.parseSquawk("SQK 0048"));
        assertTrue(eight.getMessage().contains("八进制"));
        assertThrows(V2DomainException.class, () -> TransponderCommandParsers.parseSquawk("SQK 042"));
        assertThrows(V2DomainException.class, () -> TransponderCommandParsers.parseSquawk("SQK 00042"));
        V2DomainException zero = assertThrows(V2DomainException.class,
                () -> TransponderCommandParsers.parseSquawk("SQK 0000"));
        assertTrue(zero.getMessage().contains("0000"));
    }

    @Test
    void givenSsrModeNmlAndIdentWhenBuiltThenFieldSemanticsHold() {
        assertEquals("C", TransponderCommandParsers.parseSsrMode("SSRMODE C").get("ssrMode"));
        assertThrows(V2DomainException.class,
                () -> TransponderCommandParsers.parseSsrMode("SSRMODE X"));

        Map<String, Object> nml = TransponderCommandParsers.buildNormalRestore();
        assertEquals(Arrays.asList("BUSINESS_FIELD:SQK", "BUSINESS_FIELD:SSRMODE"),
                nml.get("lockConflictKeys"), "NML 同事务锁定双冲突键");

        Map<String, Object> ident = TransponderCommandParsers.buildIdent(null);
        assertEquals(18, ident.get("durationSeconds"), "未配置默认 18 秒");
        assertEquals(Boolean.TRUE, ident.get("restartsOnRepeat"), "重复成功重新计时");
        assertEquals("TRANSPONDER_IDENT", ident.get("conflictKeySuffix"),
                "IDENT 独立冲突键，不等同 profile CLR");

        assertEquals(5, TransponderCommandParsers.buildIdent(5).get("durationSeconds"));
        assertEquals(30, TransponderCommandParsers.buildIdent(30).get("durationSeconds"));
        assertThrows(V2DomainException.class, () -> TransponderCommandParsers.buildIdent(4));
        assertThrows(V2DomainException.class, () -> TransponderCommandParsers.buildIdent(31));

        // 自动清除按仿真秒
        assertEquals(true, TransponderCommandParsers.identExpired(100, 18, 118));
        assertEquals(false, TransponderCommandParsers.identExpired(100, 18, 117.9));
    }

    @Test
    void givenNspeedWhenBuiltThenAdapterConfirmedManagedRestore() {
        Map<String, Object> nspeed = TransponderCommandParsers.buildNormalSpeed();
        assertEquals(Boolean.TRUE, nspeed.get("adapterRequired"), "NSPEED 复用 SPEED 通道");
        assertEquals("RESTORE_MANAGED", nspeed.get("action"));
        assertTrue(String.join(";", (java.util.List<String>) nspeed.get("rules"))
                .contains("性能降级"));
    }

    // ---------------------------------------------------------------- profile

    @Test
    void givenProfileContractWhenValidatedThenFixedEnumsEnforced() {
        assertEquals("SPECIAL_MARK_APPLY",
                SpecialProfileSchemaRegistry.validateAdapterAction("SPECIAL_MARK_APPLY"));
        V2DomainException nativeCmd = assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.validateAdapterAction("PAN PAN"));
        assertTrue(nativeCmd.getMessage().contains("BlueSky 原生命令"));

        SpecialProfileSchemaRegistry.validateOverridePolicy("ALL_OR_NOTHING");
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.validateOverridePolicy("MAYBE"));
        SpecialProfileSchemaRegistry.validateRecoveryPolicy("HOLD_RESULT");
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.validateRecoveryPolicy("REBOOT"));

        SpecialProfileSchemaRegistry.validateDuration(1);
        SpecialProfileSchemaRegistry.validateDuration(3600);
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.validateDuration(0));
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.validateDuration(3601));

        // DECOMP 必须 N/S/CLR 三模式；ID 用 DEFAULT（详细设计 7.8 固定契约）
        assertEquals(Arrays.asList("N", "S", "CLR"),
                SpecialProfileSchemaRegistry.requiredModes("DECOMP"));
        assertEquals(Arrays.asList("DEFAULT"), SpecialProfileSchemaRegistry.requiredModes("ID"));
    }

    @Test
    void givenProfileStateMachineWhenTransitionsTriedThenPublishedIsImmutable() {
        assertEquals("PUBLISHED", SpecialProfileSchemaRegistry.transition("DRAFT", "PUBLISH"));
        assertEquals("DRAFT", SpecialProfileSchemaRegistry.transition("DRAFT", "UPDATE"));
        assertEquals("RETIRED", SpecialProfileSchemaRegistry.transition("PUBLISHED", "RETIRE"));

        V2DomainException publishedPut = assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.transition("PUBLISHED", "UPDATE"));
        assertEquals("PROFILE_IMMUTABLE", publishedPut.code());
        assertEquals(409, publishedPut.httpStatus());
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.transition("RETIRED", "PUBLISH"));
        assertThrows(V2DomainException.class,
                () -> SpecialProfileSchemaRegistry.transition("DRAFT", "RETIRE"));
    }

    @Test
    void givenProfilesWhenCanonicalizedThenChecksumStableAndOrderInsensitive() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("adapterAction", "SPECIAL_MARK_APPLY");
        a.put("durationSeconds", 300);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("durationSeconds", 300);
        b.put("adapterAction", "SPECIAL_MARK_APPLY");

        assertEquals(SpecialProfileSchemaRegistry.canonicalizeAndChecksum(a),
                SpecialProfileSchemaRegistry.canonicalizeAndChecksum(b),
                "键序不影响 checksum（规范化排序）");
        assertEquals(64, SpecialProfileSchemaRegistry.canonicalizeAndChecksum(a).length());

        b.put("durationSeconds", 301);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                SpecialProfileSchemaRegistry.canonicalizeAndChecksum(a),
                SpecialProfileSchemaRegistry.canonicalizeAndChecksum(b));

        // Schema 版本形态
        SpecialProfileSchemaRegistry.validateSchemaVersion("special-profile/1",
                new LinkedHashMap<>());
        assertThrows(V2DomainException.class, () -> SpecialProfileSchemaRegistry
                .validateSchemaVersion("profile/1", new LinkedHashMap<>()));
    }
}
