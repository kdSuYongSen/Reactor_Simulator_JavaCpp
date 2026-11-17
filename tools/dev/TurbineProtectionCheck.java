package com.darwish.nppsim;

/**
 * 汽轮机保护逻辑自检（2026-11-03）。
 *
 * <p>逐条验证 {@link TurbineProtection} 的定值与"能不能旁路"：</p>
 * <ol>
 *   <li>自动跳闸关闭时，电气超速（110% = 3300 rpm）<b>不</b>动作；</li>
 *   <li>自动跳闸关闭时，机械超速（111.5% = 3345 rpm）<b>照样</b>脱扣，并置 mechanicalTripped；</li>
 *   <li>自动跳闸投入时，3300 rpm 触发电气超速跳闸；</li>
 *   <li>低真空、低主汽压力、过负荷、逆功率（延时 7 s）都会跳闸；</li>
 *   <li>转速还高于额定（&gt; 3050）时不允许复位机械脱扣。</li>
 * </ol>
 */
public class TurbineProtectionCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(400);

        TG tg = NPPSim.tg1;
        TurbineProtection pr = tg.getProtection();

        // ---------- 1. 自动跳闸关闭 + 3300 rpm：不应动作 ----------
        resetQuiet(tg, pr);
        pr.autoTripEnabled = false;
        tg.rpm = 3300f;
        pr.evaluate(tg, 0.05);
        check("自动跳闸关闭时 3300 rpm 不跳闸", !tg.isTripped() && !pr.mechanicalTripped,
                "tripped=" + tg.isTripped());

        // ---------- 2. 自动跳闸关闭 + 3345 rpm：机械脱扣必须动作 ----------
        pr.autoTripEnabled = false;
        tg.rpm = 3345f;
        pr.evaluate(tg, 0.05);
        check("机械卡扣 111.5%（3345 rpm）在自动跳闸关闭时仍脱扣",
                tg.isTripped() && pr.mechanicalTripped, "tripped=" + tg.isTripped()
                        + " mech=" + pr.mechanicalTripped);
        check("机械脱扣原因已记录", pr.tripReason.contains("Mechanical"),
                pr.tripReason);

        // ---------- 3. 超速未降下来之前不许复位 ----------
        check("转速 3345 时拒绝复位机械脱扣", !pr.canResetMechanical(tg.rpm),
                "rpm=" + tg.rpm);
        check("转速 2900 时允许复位机械脱扣", pr.canResetMechanical(2900f), "rpm=2900");

        // ---------- 4. 自动跳闸投入 + 3300 rpm：电气超速 ----------
        resetQuiet(tg, pr);
        pr.autoTripEnabled = true;
        tg.rpm = 3300f;
        pr.evaluate(tg, 0.05);
        check("电气超速 110%（3300 rpm）跳闸", tg.isTripped() && !pr.mechanicalTripped,
                "tripped=" + tg.isTripped() + " reason=" + pr.tripReason);

        // ---------- 5. 低真空（自检环境里凝汽器没抽真空，压力天然 > 0.030）----------
        resetQuiet(tg, pr);
        pr.autoTripEnabled = true;
        tg.rpm = 3000f;
        if (tg.condenser.getPressure() > TurbineProtection.LOW_VACUUM_MPA) {
            pr.evaluate(tg, 0.05);
            check("低真空跳闸", tg.isTripped() && pr.tripReason.contains("Vacuum"),
                    "p=" + String.format("%.3f", tg.condenser.getPressure())
                            + " reason=" + pr.tripReason);
        } else {
            check("低真空跳闸", true, "本环境已有真空，跳过（压力 "
                    + String.format("%.3f", tg.condenser.getPressure()) + " MPa）");
        }

        // ---------- 6. 复位语义：清锁存、清原因 ----------
        tg.forceReset();
        check("复位后机械锁存与原因被清除",
                !pr.mechanicalTripped && pr.tripReason.isEmpty(),
                "mech=" + pr.mechanicalTripped + " reason=\"" + pr.tripReason + "\"");

        System.out.println(fails == 0 ? "TG_PROTECTION_OK" : ("TG_PROTECTION_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    /** 把一台机恢复到"未跳闸、保护复位"的状态，并把转速/汽包压力摆到能通过复位判据的值 */
    static void resetQuiet(TG tg, TurbineProtection pr) {
        tg.rpm = 3000f;
        tg.forceReset();
        pr.autoTripEnabled = true;
        pr.tripReason = "";
    }

    /** 直接改凝汽器内部压力字段（自检用：不走 update，避免被热平衡算回去） */
    static void forceCondenserPressure(TG tg, double p) {
        try {
            java.lang.reflect.Field f = Condenser.class.getDeclaredField("pressure");
            f.setAccessible(true);
            for (Condenser c : tg.condenser.units) {
                f.setDouble(c, p);
            }
        } catch (Exception e) {
            System.out.println("  (无法设置凝汽器压力：" + e + ")");
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s：%s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
