package com.darwish.nppsim;

/**
 * AZ 升级链自检（2026-11-03）。
 *
 * <p>验：AZ-1（一回路压力 7.26 MPa）先只报警、不动棒；持续越限 8 s 后升级成 AZ-1K，
 * 这时才插棒。用的手法是把汽包压力直接顶到 7.30 MPa。</p>
 */
public class AzEscalationCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        AutoControl ac = NPPSim.autoControl;
        ControlRodChannel fasr = null;
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof FASRChannel) {
                    fasr = (ControlRodChannel) c;
                    break;
                }
            }
            if (fasr != null) {
                break;
            }
        }
        check("找到 FASR 棒", fasr != null, "");
        fasr.setPosition(0.0f);

        // 把 1 号汽包压力顶到 7.30 MPa（AZ-1 定值 7.26，AZ-1K 定值 7.50 以下）
        forcePressure(NPPSim.mcc.drum1, 7.30);

        ac.az1Signal.update();
        Thread.sleep(200);
        check("AZ-1 先动作（压力信号）", ac.az1Signal.isTripped(), "");
        check("AZ-1 不插棒", fasr.getPosition() < 0.01f, "pos=" + fasr.getPosition());
        check("AZ-1 不触发 AZ-1K", !ac.az1Control.isTripped(), "");

        // 持续越限：连续推 8.5 s 的 tick（直接驱动升级计数，避免仿真线程把压力算回去）
        java.lang.reflect.Method count = ac.az1Signal.getClass()
                .getDeclaredMethod("countEscalation");
        count.setAccessible(true);
        for (int i = 0; i < 170 && !ac.az1Control.isTripped(); i++) {
            count.invoke(ac.az1Signal);
        }
        Thread.sleep(300);
        check("持续 8 s 后升级 AZ-1K", ac.az1Control.isTripped(), "");
        check("AZ-1K 插棒", fasr.getPosition() > 0.0f || isScram(fasr), "pos=" + fasr.getPosition());

        ac.resetAllAz();
        Thread.sleep(300);
        check("复位后 AZ-1 / AZ-1K 都清掉",
                !ac.az1Signal.isTripped() && !ac.az1Control.isTripped(), "");

        System.out.println(fails == 0 ? "AZ_ESCALATION_OK" : ("AZ_ESCALATION_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void forcePressure(Object drum, double mpa) throws Exception {
        Class<?> c = drum.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("pressure");
                f.setAccessible(true);
                f.setDouble(drum, mpa);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
    }

    static boolean isScram(ControlRodChannel rod) throws Exception {
        java.lang.reflect.Field f = ControlRodChannel.class.getDeclaredField("scram");
        f.setAccessible(true);
        return f.getBoolean(rod);
    }

    static void check(String name, boolean ok, String detail) {
        if (!ok) {
            fails++;
        }
        System.out.printf("[%s] %s %s%n", ok ? "OK" : "FAIL", name, detail);
    }
}
