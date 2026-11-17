package com.darwish.nppsim;

/**
 * AZ 分层逻辑自检（2026-11-03 重写）。
 *
 * <p>规则：**只有 AZ-1K 和 BSM 会自动动棒**；AZ-1~AZ-4、AZ-6 只报警 + 联锁，
 * 面板上它们的按钮也只是"发出该信号"，不再插棒。AZ-5 仍是手动紧急停堆（全插）。</p>
 */
public class AzRodLogicCheck {

    static int fails = 0;

    public static void main(String[] a) throws Exception {
        Localization.init();
        UiStyle.applyLaf();
        Fonts.init();
        Loader.tables = new SteamTables();
        Loader.soundProvider = new SoundProvider();
        new NPPSim();
        Thread.sleep(800);

        ControlRodChannel manual = null;
        ControlRodChannel fasr = null;
        outer:
        for (java.util.ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (Channel c : row) {
                if (c instanceof ControlRodChannel) {
                    if (manual == null && c.getClass() == MCRChannel.class) {
                        manual = (ControlRodChannel) c;
                    } else if (fasr == null && c instanceof FASRChannel) {
                        fasr = (ControlRodChannel) c;
                    }
                }
                if (manual != null && fasr != null) {
                    break outer;
                }
            }
        }
        check("找到手动棒 MCR", manual != null, "");
        check("找到快速停堆棒 FASR", fasr != null, "");

        AutoControl ac = NPPSim.autoControl;

        // ---------- AZ-6（主蒸汽低压 / 失汽）：只亮灯 + 切机，不动棒 ----------
        manual.setPosition(0.0f);
        fasr.setPosition(0.0f);
        ac.az6Control.trip();
        Thread.sleep(400);
        check("AZ-6 只发信号（灯亮）", ac.az6Control.isTripped(), "");
        check("AZ-6 不插 FASR", fasr.getPosition() < 0.01f && !isScram(fasr),
                "pos=" + fasr.getPosition());
        check("AZ-6 不插手动棒", manual.getPosition() < 0.01f && !isScram(manual),
                "pos=" + manual.getPosition());
        check("AZ-6 联锁汽机脱扣", NPPSim.tg1.isTripped() || NPPSim.tg2.isTripped(),
                "tg1=" + NPPSim.tg1.isTripped());
        ac.az6Control.reset();
        Thread.sleep(200);

        // ---------- AZ-2（106%）：只把 АР 设定压下来，不动棒 ----------
        manual.setPosition(0.0f);
        fasr.setPosition(0.0f);
        ac.automaticRodController.setSetpoint(4800.0);      // 先给个额定设定
        ac.az2Control.trip();
        Thread.sleep(300);
        double sp = ac.automaticRodController.getSetpoint();
        check("AZ-2 不插棒", fasr.getPosition() < 0.01f && manual.getPosition() < 0.01f,
                "fasr=" + fasr.getPosition() + " manual=" + manual.getPosition());
        check("AZ-2 把 АР 设定压到 98%（4704 MW）", Math.abs(sp - 4704.0) < 1.0, "setpoint=" + sp);
        ac.az2Control.reset();
        Thread.sleep(200);

        // ---------- AZ-5（手动紧急停堆）：全插 ----------
        manual.setPosition(0.0f);
        fasr.setPosition(0.0f);
        ac.az5Control.trip();
        Thread.sleep(400);
        check("AZ-5 插 FASR", fasr.getPosition() > 0.0f || isScram(fasr), "pos=" + fasr.getPosition());
        check("AZ-5 插手动棒", manual.getPosition() > 0.0f || isScram(manual), "pos=" + manual.getPosition());
        ac.az5Control.reset();
        Thread.sleep(300);

        // ---------- AZ-1K（快速保护）：全插 ----------
        manual.setPosition(0.0f);
        fasr.setPosition(0.0f);
        ac.az1Control.trip("SelfTest");
        Thread.sleep(400);
        check("AZ-1K 插 FASR", fasr.getPosition() > 0.0f || isScram(fasr), "pos=" + fasr.getPosition());
        check("AZ-1K 插手动棒", manual.getPosition() > 0.0f || isScram(manual), "pos=" + manual.getPosition());
        ac.az1Control.reset();
        Thread.sleep(300);

        // ---------- AZ-1 是独立的压力信号，不共用 AZ-1K 的锁存 ----------
        check("AZ-1 与 AZ-1K 是两个独立信号", !ac.az1Signal.isTripped(),
                "az1=" + ac.az1Signal.isTripped() + " az1k=" + ac.az1Control.isTripped());

        System.out.println(fails == 0 ? "AZ_ROD_LOGIC_OK" : ("AZ_ROD_LOGIC_FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
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
