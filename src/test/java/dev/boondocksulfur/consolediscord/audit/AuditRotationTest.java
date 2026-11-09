package dev.boondocksulfur.consolediscord.audit;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRotationTest {

    @TempDir
    File dir;

    private Plugin plugin() {
        Logger logger = Logger.getLogger("AuditRotationTest");
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dir;
                    case "getLogger" -> logger;
                    default -> null;
                });
    }

    private static void fill(File file, String marker) throws Exception {
        Files.writeString(file.toPath(), marker + "\n" + "x".repeat(1024 * 1024));
    }

    private static String firstLine(File file) throws Exception {
        return Files.readAllLines(file.toPath()).get(0);
    }

    @Test
    void keepsNumberedBackupsAndDropsOldest() throws Exception {
        File log = new File(dir, "audit.log");
        for (int round = 1; round <= 4; round++) {
            AuditLogger audit = new AuditLogger(plugin(), "audit.log", false, "", 1, 2);
            fill(log, "round" + round);
            audit.logCommand("1", "user", "list", true);
            audit.shutdown();
        }
        assertEquals("round4", firstLine(new File(dir, "audit.log.1")));
        assertEquals("round3", firstLine(new File(dir, "audit.log.2")));
        assertFalse(new File(dir, "audit.log.3").exists());
        assertTrue(Files.readString(log.toPath()).contains("executed: list"));
    }

    @Test
    void legacyBackupJoinsNumberedOnes() throws Exception {
        File log = new File(dir, "audit.log");
        Files.writeString(new File(dir, "audit.log.old").toPath(), "legacy\n");
        AuditLogger audit = new AuditLogger(plugin(), "audit.log", false, "", 1, 3);
        fill(log, "current");
        audit.logCommand("1", "user", "list", true);
        audit.shutdown();
        assertEquals("current", firstLine(new File(dir, "audit.log.1")));
        assertEquals("legacy", firstLine(new File(dir, "audit.log.2")));
        assertFalse(new File(dir, "audit.log.old").exists());
    }
}
