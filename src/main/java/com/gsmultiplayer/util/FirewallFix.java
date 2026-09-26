package com.gsmultiplayer.util;

import java.io.File;

/**
 * Recreates Windows Firewall inbound rules for the running Java runtime
 * (the same fix Open2Online's "Recreate Firewall Rules" button performs).
 * Requires a UAC confirmation from the user; on other systems it is a no-op.
 */
public final class FirewallFix {

    private static final String RULE_NAME = "GS Multiplayer Java";

    private FirewallFix() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }

    /**
     * Runs an elevated netsh command that deletes and re-adds inbound allow rules
     * for java.exe/javaw.exe of the current JRE. Returns false immediately on
     * non-Windows systems or when the process could not be started at all
     * (the UAC dialog itself is asynchronous).
     */
    public static boolean recreateRules() {
        if (!isWindows()) {
            return false;
        }
        String home = System.getProperty("java.home", "");
        StringBuilder allow = new StringBuilder();
        for (String exe : new String[]{"java.exe", "javaw.exe"}) {
            File f = new File(home, "bin\\" + exe);
            if (f.isFile()) {
                if (allow.length() > 0) {
                    allow.append(" ");
                }
                allow.append("netsh advfirewall firewall delete rule name=\"").append(RULE_NAME)
                        .append("\" program=\"").append(f.getAbsolutePath()).append("\" >nul & ")
                        .append("netsh advfirewall firewall add rule name=\"").append(RULE_NAME)
                        .append("\" dir=in action=allow program=\"").append(f.getAbsolutePath())
                        .append("\" enable=yes profile=any >nul");
            }
        }
        if (allow.length() == 0) {
            GsLog.warn("FirewallFix: java.exe not found in " + home);
            return false;
        }
        try {
            String script = allow.toString();
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Start-Process cmd -ArgumentList '/c " + script.replace("'", "''")
                            + "' -Verb RunAs -WindowStyle Hidden");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
            GsLog.info("FirewallFix: elevated rule recreation requested");
            return true;
        } catch (Exception e) {
            GsLog.warn("FirewallFix failed: " + e.getMessage());
            return false;
        }
    }
}
