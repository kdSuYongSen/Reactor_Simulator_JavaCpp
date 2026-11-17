package com.darwish.nppsim;

import java.io.Serializable;
import java.util.ArrayList;

/**
 * 全厂电气系统（2026-11-12 新增；同日按参考图改为 750 kV 电压等级）。
 *
 * <pre>
 *   20 kV 发电机出口母线 ──Q0── 主变 20/750 kV ──Q1── 750 kV 主电网
 *        │                                              │
 *     TG-1 / TG-2                            厂用变 750/6 kV  Q2/Q3
 *                                                       │
 *   110 kV 辅助（厂外独立进线）──Q4/Q5──→ 6 kV 常用母线 A / B（母联 Q6）
 *                                              │Q7/Q8
 *                                        6 kV 应急母线 A / B ← 柴油机 2×3.2 MW
 *                                              │Q9/Q10
 *                                        380 V 三相 → 直流 220 V（充电机 Q11 + 2000 Ah 蓄电池）
 * </pre>
 *
 * <p>几个建模口径：</p>
 * <ul>
 *   <li>厂用电负荷取**真实泵电流**（每台泵 {@code powerUsage}）+ 固定厂用负荷，
 *       所以启停泵时"厂用电 / 上网功率"会立刻变化；</li>
 *   <li>母线带电判据是"至少有一路电源可用"，不做潮流迭代（机组容量远大于厂用负荷）；</li>
 *   <li>110 kV 是**厂外变电站的独立进线**，不由本厂 750 kV 派生 ——
 *       所以解列 750 kV 不会把厂用备用电源一起带走；</li>
 *   <li>用设备级联锁代替保护定值表：母线失电 → 该段动力负荷跳闸、柴油机自启、
 *       直流转蓄电池供电，事件只在状态翻转时记一条。</li>
 * </ul>
 */
public class ElectricalGrid implements Serializable {

    private static final long serialVersionUID = 2L;

    // ---------------------------------------------------------------- 电压等级
    public static final double V_GEN_20 = 20.0;      // 发电机出口
    public static final double V_GRID_750 = 750.0;   // 主电网
    public static final double V_AUX_110 = 110.0;    // 辅助电源（厂外来）
    public static final double V_UNIT_6 = 6.0;       // 厂用常用 / 应急
    public static final double V_UNIT_04 = 0.4;      // 380 V 三相
    public static final double V_DC = 220.0;         // 直流控制电源

    // ---------------------------------------------------------------- 容量
    /** 厂用变（750/6 kV）单台容量 MVA */
    static final double UNIT_AUX_MVA = 25.0;
    /** 110 kV 备用变单台容量 */
    static final double RESERVE_MVA = 25.0;
    /** 6/0.4 kV 单台容量 */
    static final double AUX_04_MVA = 1.6;
    /** 应急柴油机单台容量 MW */
    static final double DIESEL_MW = 3.2;
    /** 蓄电池容量 Ah */
    static final double BATTERY_AH = 2000.0;

    // ---------------------------------------------------------------- 固定厂用负荷（MW）
    static final double FIXED_6A_MW = 27.0;
    static final double FIXED_6B_MW = 27.0;
    static final double FIXED_380_MW = 6.0;
    /** 直流充电机（含 UPS）取自 380 V 的功率 */
    static final double CHARGER_MW = 0.35;

    // ---------------------------------------------------------------- 开关状态（可操作）
    /** 20 kV 发电机出口断路器（分开 = 机组与主变解列） */
    boolean genBreaker20 = true;
    /** 750 kV 上网断路器（分开 = 机组孤岛运行） */
    boolean gridBreaker = true;
    /**
     * Q1 钥匙锁（2026-11-14）：true = 锁定，断路器不能操作。
     * 面板上左下角红灯常亮表示锁定；扭钥匙（I 位）解锁后才能分/合。
     */
    boolean gridBreakerLocked = true;
    /** 110 kV 进线断路器（厂外辅助电源） */
    boolean line110Breaker = true;
    /** 厂用变 750/6 kV 高压侧：750 kV → 6 kV 常用 A/B */
    boolean unitBreakerA = true, unitBreakerB = true;
    /** 备用进线：110 kV → 6 kV 常用 A/B */
    boolean reserveBreakerA = true, reserveBreakerB = true;
    /** 6 kV A-B 母联（正常断开） */
    boolean busTie = false;
    /** 应急进线：6 kV 常用 → 应急 A/B */
    boolean emergencyBreakerA = true, emergencyBreakerB = true;
    /** 6/0.4 kV 变压器 */
    boolean transformer04A = true, transformer04B = true;
    /** 直流充电机运行 */
    boolean chargerOn = true;
    /**
     * 应急柴油发电机组：共 4 台，每段 6 kV 应急母线各 2 台（1 号工作 / 2 号备用）。
     * 编号 0…3 = DG-A1 / DG-A2 / DG-B1 / DG-B2。
     */
    static final int DIESEL_COUNT = 4;

    /** 机组所属应急母线：0 = A 段（0/1 号机），1 = B 段（2/3 号机） */
    static int dieselBus(int unit) {
        return unit < 2 ? 0 : 1;
    }

    /** 机组编号（界面用） */
    static String dieselTag(int unit) {
        return new String[]{"A1", "A2", "B1", "B2"}[unit];
    }

    /** 柴油机运行状态（手动或自动投入） */
    boolean[] dieselRun = new boolean[DIESEL_COUNT];
    /** 柴油机自动启动使能 */
    boolean dieselAutoStart = true;
    /** 手动强投（自动逻辑不再停它） */
    boolean[] dieselManual = new boolean[DIESEL_COUNT];

    // ---------------------------------------------------------------- 派生量
    double generationMW;
    double auxLoadMW;
    double exportMW;
    double frequencyHz = 50.0;

    boolean bus20Live, bus750Live, bus110Live, bus6ALive, bus6BLive, bus6AeLive, bus6BeLive;
    boolean bus04Live, dcLive;

    double volt20, volt750, volt110, volt6A, volt6B, volt6Ae, volt6Be, volt04, voltDC;

    /** 各段实际负荷 MW */
    double load6A, load6B, load6Ae, load6Be, load380, loadDC;

    double batterySoc = 1.0;

    // ---- 柴油机机械/热工量（2026-11-12 晚：应急发电窗口用；2026-11-13 扩到 4 台）----
    /** 额定转速（4 极机 50 Hz = 1500 rpm） */
    static final double DIESEL_RATED_RPM = 1500.0;
    /** 单台油箱容积 m³ */
    static final double DIESEL_TANK_M3 = 6.0;
    /** 满负荷燃油消耗 kg/s（≈0.19 kg/s → 0.23 m³/h，油箱约 4 %/h） */
    static final double DIESEL_KGS_FULL = 0.19;
    /** 柴油密度 kg/m³ */
    static final double DIESEL_FUEL_DENSITY = 840.0;

    /** 各机出力（0…1 额定） */
    double[] dieselOut = new double[DIESEL_COUNT];
    double[] rpm = new double[DIESEL_COUNT];
    /** 油箱剩余比例 0~1 */
    double[] fuel = {1.0, 1.0, 1.0, 1.0};
    /** 冷却水 / 润滑油 / 排烟温度 °C */
    double[] coolant = {25, 25, 25, 25};
    double[] oilTemp = {25, 25, 25, 25};
    double[] exhaust = {25, 25, 25, 25};
    /** 机端电压 kV、电流 A、累计运行小时 */
    double[] voltDg = new double[DIESEL_COUNT];
    double[] ampDg = new double[DIESEL_COUNT];
    double[] hours = new double[DIESEL_COUNT];
    /** 发电机出口断路器（合 = 带该段应急母线） */
    boolean[] dieselBreaker = {true, true, true, true};

    // ---- 柴油机润滑油系统（2026-11-13：应急发电窗口的 A~E 油泵开关）----
    /**
     * 五台润滑油泵：A/B/C/D 分别供 DG-A1 / DG-A2 / DG-B1 / DG-B2，E 为公用应急泵
     * （同时向四台机供油）。默认全开，与"备用机组随时可启"的工况一致。
     */
    boolean[] lubePumpOn = {true, true, true, true, true};
    /** 润滑油母管压力 bar（正常 4.2，无泵运行时泄到 0） */
    double[] lubePress = {4.2, 4.2, 4.2, 4.2};
    /** 该机当前运行的润滑油泵台数（本机泵 + 公用泵） */
    int[] lubeRunning = {2, 2, 2, 2};
    /** 低油压持续计时（用于跳机），以及"已因低油压跳闸"标记 */
    private int[] lubeLowTicks = new int[DIESEL_COUNT];
    private boolean[] lubeTripAnnounced = new boolean[DIESEL_COUNT];
    /** 低油压导致自启失败（每段母线只报一次事件） */
    private boolean[] autoStartBlocked = new boolean[2];

    /** 单台润滑油泵功率 MW（3.2 MW 级柴油机的 380 V 辅助油泵约 18 kW） */
    static final double LUBE_PUMP_MW = 0.018;
    /** 起机所需最低润滑油压 bar */
    static final double LUBE_START_MIN_BAR = 1.5;
    /** 运行中低油压跳机阈值 bar / 延时 s */
    static final double LUBE_TRIP_BAR = 1.0;
    static final double LUBE_TRIP_DELAY_S = 3.0;

    /** 燃油耗尽标记（只报一次事件） */
    private boolean[] fuelOutAnnounced = new boolean[DIESEL_COUNT];

    // 内部计时/边沿检测
    /** 每段应急母线失电计时（0 = A 段，1 = B 段） */
    private int[] deadTicksBus = new int[2];
    private boolean last6A = true, last6B = true, last110 = true, lastDC = true;
    private boolean lastEmergA = true, lastEmergB = true, last750 = true, last20 = true;
    private boolean importAnnounced = false;

    static final double DIESEL_START_S = 10.0;
    static final double DIESEL_RAMP_S = 8.0;

    // ================================================================ 更新

    void update() {
        final double dt = 0.05;

        TG tgA = NPPSim.tg1;
        TG tgB = NPPSim.tg2;
        generationMW = Math.max(0.0, tgA == null ? 0.0 : tgA.getGeneratorLoad())
                + Math.max(0.0, tgB == null ? 0.0 : tgB.getGeneratorLoad());
        boolean generatorOnline = (tgA != null && tgA.isSynced() && !tgA.isTripped())
                || (tgB != null && tgB.isSynced() && !tgB.isTripped());

        // ---- 1) 20 kV 发电机出口 → 主变 → 750 kV ----
        bus20Live = generatorOnline && genBreaker20;
        // 750 kV 母线：由电网供电，或由本机经主变反送（解列后仍能带厂用）
        bus750Live = gridBreaker || bus20Live;
        bus110Live = line110Breaker;

        // ---- 2) 6 kV 常用段 ----
        boolean supplyA = (unitBreakerA && bus750Live) || (reserveBreakerA && bus110Live);
        boolean supplyB = (unitBreakerB && bus750Live) || (reserveBreakerB && bus110Live);
        bus6ALive = supplyA || (busTie && supplyB);
        bus6BLive = supplyB || (busTie && supplyA);

        // ---- 3) 应急段（4 台柴油机，每段 2 台）----
        dieselLogic(dt, bus6ALive, bus6BLive);
        boolean dieselLiveA = false;
        boolean dieselLiveB = false;
        for (int u = 0; u < DIESEL_COUNT; u++) {
            if (dieselBreaker[u] && dieselOut[u] > 0.05) {
                if (dieselBus(u) == 0) {
                    dieselLiveA = true;
                } else {
                    dieselLiveB = true;
                }
            }
        }
        bus6AeLive = (emergencyBreakerA && bus6ALive) || dieselLiveA;
        bus6BeLive = (emergencyBreakerB && bus6BLive) || dieselLiveB;

        // ---- 4) 380 V / 直流 ----
        bus04Live = (transformer04A && (bus6ALive || bus6AeLive))
                || (transformer04B && (bus6BLive || bus6BeLive));
        dcLogic(dt, bus04Live);

        // ---- 5) 负荷 ----
        computeLoads();

        // ---- 6) 功率平衡 / 频率 ----
        auxLoadMW = load6A + load6B + load6Ae + load6Be + load380 + loadDC;
        if (gridBreaker) {
            exportMW = generationMW - auxLoadMW;
            frequencyHz = 50.0;
        } else {
            exportMW = 0.0;
            if (generationMW < 0.5 && auxLoadMW < 0.5) {
                frequencyHz = 0.0;
            } else {
                frequencyHz = clamp(50.0 + (generationMW - auxLoadMW) * 0.0025, 47.0, 53.0);
            }
        }
        updateVoltages();

        // ---- 7) 失电联锁 ----
        enforceSupplies();

        // ---- 8) 事件 ----
        announce();
    }

    /**
     * 应急柴油机的启停逻辑（每段母线 2 台：1 号工作 / 2 号备用）。
     *
     * <p>某段 6 kV 常用母线失电 10 s → 自动启动该段的 1 号机；若 1 号机润滑油压没建立，
     * 自动改启 2 号机（工作/备用互为后备）。母线恢复后，非手动强投的机组自动停机。</p>
     */
    private void dieselLogic(double dt, boolean liveA, boolean liveB) {
        boolean[] liveBus = {liveA, liveB};
        int startTicks = (int) Math.round(DIESEL_START_S / dt);

        for (int bus = 0; bus < 2; bus++) {
            boolean live = liveBus[bus];
            deadTicksBus[bus] = live ? 0 : deadTicksBus[bus] + 1;

            if (!dieselAutoStart) {
                continue;
            }
            if (live) {
                autoStartBlocked[bus] = false;
                // 母线恢复：把自动投入的机组停掉（手动强投的保留）
                for (int u = bus * 2; u < bus * 2 + 2; u++) {
                    if (dieselRun[u] && !dieselManual[u]) {
                        dieselRun[u] = false;
                        event("Diesel generator " + dieselTag(u) + " stopped (bus restored)");
                    }
                }
                continue;
            }
            if (deadTicksBus[bus] < startTicks || anyRunning(bus)) {
                continue;
            }
            // 先试工作机，油压不够再试备用机
            int pick = -1;
            for (int u = bus * 2; u < bus * 2 + 2; u++) {
                if (lubePress[u] >= LUBE_START_MIN_BAR) {
                    pick = u;
                    break;
                }
            }
            if (pick >= 0) {
                dieselRun[pick] = true;
                autoStartBlocked[bus] = false;
                event("Diesel generator " + dieselTag(pick) + " auto-start (loss of 6 kV bus "
                        + (bus == 0 ? "A" : "B") + ")");
            } else if (!autoStartBlocked[bus]) {
                // 两台都没建起润滑油压：只报一次，避免每拍刷屏
                autoStartBlocked[bus] = true;
                event("Diesel auto-start blocked on 6 kV bus " + (bus == 0 ? "A" : "B")
                        + " - low lube oil pressure");
            }
        }

        double ramp = dt / DIESEL_RAMP_S;
        for (int u = 0; u < DIESEL_COUNT; u++) {
            dieselOut[u] = dieselRun[u] ? Math.min(1.0, dieselOut[u] + ramp)
                    : Math.max(0.0, dieselOut[u] - ramp);
        }

        dieselDetail(dt);
    }

    /** 该段母线上是否已有柴油机在运行 */
    private boolean anyRunning(int bus) {
        for (int u = bus * 2; u < bus * 2 + 2; u++) {
            if (dieselRun[u]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 柴油机的转速 / 燃油 / 温度 / 电压电流（应急发电窗口的仪表数据）。
     *
     * <p>转速按 8 s 升速、6 s 停机；频率取 4 极机的 f = n/30（1500 rpm → 50 Hz）；
     * 温度是一阶惯性（τ≈30 s）跟着负荷走；燃油按满负荷 0.19 kg/s 消耗，
     * 烧完自动停机并记一条事件。这些量只用于显示与"油耗"手感，不参与电网功率平衡
     * （功率仍由 {@link #dieselOut} 给）。</p>
     */
    private void dieselDetail(double dt) {
        // ---- 润滑油系统：泵组 → 母管压力 → 低油压保护 ----
        for (int u = 0; u < DIESEL_COUNT; u++) {
            lubeRunning[u] = countLubePumps(u);
            lubePress[u] = stepTo(lubePress[u], lubeTargetBar(lubeRunning[u], dieselOut[u]), dt, 1.4);
        }
        lubeProtection(dt);

        for (int u = 0; u < DIESEL_COUNT; u++) {
            rpm[u] = stepTo(rpm[u], dieselRun[u] ? DIESEL_RATED_RPM : 0.0,
                    dt, dieselRun[u] ? DIESEL_RATED_RPM / 8.0 : DIESEL_RATED_RPM / 6.0);

            // 燃油：满负荷 0.19 kg/s，按实际出力折算
            double burn = DIESEL_KGS_FULL * Math.max(0, dieselOut[u]);
            fuel[u] = Math.max(0.0, fuel[u] - burn * dt / (DIESEL_TANK_M3 * DIESEL_FUEL_DENSITY));
            if (fuel[u] <= 0 && !fuelOutAnnounced[u]) {
                fuelOutAnnounced[u] = true;
                dieselRun[u] = false;
                dieselManual[u] = false;
                dieselOut[u] = 0;
                event("Diesel generator " + dieselTag(u) + " tripped - fuel exhausted");
            }

            // 温度：一阶惯性跟随负荷
            coolant[u] = thermalStep(coolant[u], 25 + 60 * dieselOut[u], dt, 30);
            oilTemp[u] = thermalStep(oilTemp[u], 25 + 65 * dieselOut[u], dt, 40);
            exhaust[u] = thermalStep(exhaust[u], 25 + 430 * dieselOut[u], dt, 45);

            // 机端电气量
            voltDg[u] = rpm[u] > 1 ? 6.3 * Math.min(1.0, rpm[u] / DIESEL_RATED_RPM) : 0.0;
            double mw = dieselOut[u] * DIESEL_MW;
            ampDg[u] = voltDg[u] > 0.1 ? mw * 1.0e6 / (1.732 * voltDg[u] * 1000.0 * 0.8) : 0.0;

            if (dieselRun[u]) {
                hours[u] += dt / 3600.0;
            }
        }
    }

    /**
     * 该机组正在供油的润滑油泵台数：本机泵 + 公用泵 E。
     *
     * @param unit 0…3 = DG-A1 / DG-A2 / DG-B1 / DG-B2（对应泵 A / B / C / D）
     */
    private int countLubePumps(int unit) {
        return (lubePumpOn[unit] ? 1 : 0) + (lubePumpOn[4] ? 1 : 0);
    }

    /** 母管压力目标值：一台泵约 3.9 bar，并联公用泵再抬一点，负荷高时再抬一点 */
    private static double lubeTargetBar(int pumps, double load) {
        if (pumps <= 0) {
            return 0.0;
        }
        return 3.4 + 0.25 * Math.min(1, pumps - 1) + 0.5 * Math.max(0, load);
    }

    /** 低油压保护：运行中油压低于 1.0 bar 持续 3 s 跳机 */
    private void lubeProtection(double dt) {
        for (int u = 0; u < DIESEL_COUNT; u++) {
            lubeLowTicks[u] = (dieselRun[u] && lubePress[u] < LUBE_TRIP_BAR)
                    ? lubeLowTicks[u] + 1 : 0;
            if (lubeLowTicks[u] * dt >= LUBE_TRIP_DELAY_S && !lubeTripAnnounced[u]) {
                lubeTripAnnounced[u] = true;
                dieselRun[u] = false;
                dieselManual[u] = false;
                dieselOut[u] = 0;
                event("Diesel generator " + dieselTag(u) + " tripped - low lube oil pressure");
            }
            if (lubePress[u] >= LUBE_TRIP_BAR) {
                lubeTripAnnounced[u] = false;
            }
        }
    }

    /** 以固定速率逼近目标（避免转速瞬间跳变） */
    private static double stepTo(double cur, double target, double dt, double ratePerSec) {
        double d = target - cur;
        double max = ratePerSec * dt;
        if (d > max) {
            return cur + max;
        }
        if (d < -max) {
            return cur - max;
        }
        return target;
    }

    /** 一阶惯性（温度） */
    private static double thermalStep(double cur, double target, double dt, double tau) {
        return cur + (target - cur) * Math.min(1.0, dt / tau);
    }

    private void dcLogic(double dt, boolean ac04) {
        double dischargeA = 0.0;
        if (ac04 && chargerOn) {
            batterySoc = Math.min(1.0, batterySoc + dt * 0.0006);
        } else {
            dischargeA = 160.0 + (bus04Live ? 0.0 : 60.0);
            batterySoc = Math.max(0.0, batterySoc - dt * dischargeA / 3600.0 / BATTERY_AH);
        }
        dcLive = batterySoc > 0.02;
        voltDC = dcLive ? (ac04 && chargerOn ? 231.0
                : V_DC * (0.88 + 0.12 * Math.min(1.0, batterySoc))) : 0.0;
        loadDC = (ac04 && chargerOn ? CHARGER_MW : 0.0)
                + (dcLive ? dischargeA * V_DC / 1.0e6 : 0.0);
    }

    private void computeLoads() {
        double pumpA = 0, pumpB = 0, pumpAe = 0, pumpBe = 0;
        if (NPPSim.mcc != null && NPPSim.mcc.mcp != null) {
            for (int i = 0; i < NPPSim.mcc.mcp.size(); i++) {
                double mw = pumpMW(NPPSim.mcc.mcp.get(i));
                if (i < 4) {
                    pumpA += mw;
                } else {
                    pumpB += mw;
                }
            }
        }
        pumpA += groupMW(NPPSim.mainFeedwaterPumps, 0, 4);
        pumpB += groupMW(NPPSim.mainFeedwaterPumps, 4, Integer.MAX_VALUE);
        pumpA += groupMW(NPPSim.condensate1A, 0, Integer.MAX_VALUE);
        pumpA += groupMW(NPPSim.condensate1B, 0, Integer.MAX_VALUE);
        pumpB += groupMW(NPPSim.condensate2A, 0, Integer.MAX_VALUE);
        pumpB += groupMW(NPPSim.condensate2B, 0, Integer.MAX_VALUE);
        pumpAe += groupMW(NPPSim.auxFeedwaterPumps, 0, 3);
        pumpBe += groupMW(NPPSim.auxFeedwaterPumps, 3, Integer.MAX_VALUE);

        load6A = bus6ALive ? FIXED_6A_MW + pumpA : 0.0;
        load6B = bus6BLive ? FIXED_6B_MW + pumpB : 0.0;
        // 柴油机自身的辅机（风扇/充电/润滑油泵）算在应急段负荷里
        double dgAuxA = 0, dgAuxB = 0, lubeA = 0, lubeB = 0;
        for (int u = 0; u < DIESEL_COUNT; u++) {
            if (dieselBus(u) == 0) {
                dgAuxA += dieselOut[u] * 0.15;
                lubeA += lubeRunning[u] * LUBE_PUMP_MW;
            } else {
                dgAuxB += dieselOut[u] * 0.15;
                lubeB += lubeRunning[u] * LUBE_PUMP_MW;
            }
        }
        load6Ae = bus6AeLive ? pumpAe + dgAuxA + lubeA : 0.0;
        load6Be = bus6BeLive ? pumpBe + dgAuxB + lubeB : 0.0;
        load380 = bus04Live ? FIXED_380_MW : 0.0;
    }

    private static double groupMW(ArrayList<Pump> list, int from, int to) {
        if (list == null) {
            return 0.0;
        }
        double sum = 0;
        for (int i = Math.max(0, from); i < list.size() && i < to; i++) {
            sum += pumpMW(list.get(i));
        }
        return sum;
    }

    private static double pumpMW(Pump p) {
        if (p == null) {
            return 0.0;
        }
        double kw = p.powerUsage;
        if (!Double.isFinite(kw) || kw <= 0) {
            return 0.0;
        }
        return kw / 1000.0;
    }

    private void updateVoltages() {
        volt20 = bus20Live ? V_GEN_20 * (1.0 - 0.02 * clamp(auxLoadMW / 100.0, 0, 1)) : 0.0;
        volt750 = bus750Live ? V_GRID_750 * (1.0 - 0.004 * clamp(exportMW / 1500.0, 0, 1)) : 0.0;
        volt110 = bus110Live ? V_AUX_110 * (1.0 - 0.004 * clamp(exportMW / 200.0, 0, 1)) : 0.0;
        volt6A = bus6ALive ? V_UNIT_6 * (1.0 - 0.015 * clamp(load6A / UNIT_AUX_MVA, 0, 1)) : 0.0;
        volt6B = bus6BLive ? V_UNIT_6 * (1.0 - 0.015 * clamp(load6B / UNIT_AUX_MVA, 0, 1)) : 0.0;
        volt6Ae = bus6AeLive ? V_UNIT_6 * (1.0 - 0.04 * clamp(load6Ae / DIESEL_MW, 0, 1)) : 0.0;
        volt6Be = bus6BeLive ? V_UNIT_6 * (1.0 - 0.04 * clamp(load6Be / DIESEL_MW, 0, 1)) : 0.0;
        volt04 = bus04Live ? V_UNIT_04 * 1000.0 * (1.0 - 0.02 * clamp(load380 / (AUX_04_MVA * 2), 0, 1)) : 0.0;
    }

    private void enforceSupplies() {
        tripIfDead(NPPSim.mcc == null ? null : NPPSim.mcc.mcp, 0, 4, bus6ALive, "6 kV bus A");
        tripIfDead(NPPSim.mcc == null ? null : NPPSim.mcc.mcp, 4, Integer.MAX_VALUE, bus6BLive, "6 kV bus B");
        tripIfDead(NPPSim.mainFeedwaterPumps, 0, 4, bus6ALive, "6 kV bus A");
        tripIfDead(NPPSim.mainFeedwaterPumps, 4, Integer.MAX_VALUE, bus6BLive, "6 kV bus B");
        tripIfDead(NPPSim.condensate1A, 0, Integer.MAX_VALUE, bus6ALive, "6 kV bus A");
        tripIfDead(NPPSim.condensate1B, 0, Integer.MAX_VALUE, bus6ALive, "6 kV bus A");
        tripIfDead(NPPSim.condensate2A, 0, Integer.MAX_VALUE, bus6BLive, "6 kV bus B");
        tripIfDead(NPPSim.condensate2B, 0, Integer.MAX_VALUE, bus6BLive, "6 kV bus B");
        tripIfDead(NPPSim.auxFeedwaterPumps, 0, 3, bus6AeLive, "6 kV emergency bus A");
        tripIfDead(NPPSim.auxFeedwaterPumps, 3, Integer.MAX_VALUE, bus6BeLive, "6 kV emergency bus B");
    }

    private void tripIfDead(ArrayList<Pump> list, int from, int to, boolean live, String busName) {
        if (list == null || live) {
            return;
        }
        for (int i = Math.max(0, from); i < list.size() && i < to; i++) {
            Pump p = list.get(i);
            if (p != null && p.isActive()) {
                p.setActive(false);
                event("Pump tripped - loss of " + busName + " (" + (i + 1) + ")");
            }
        }
    }

    private void announce() {
        if (last750 && !bus750Live) {
            event("750 kV MAIN BUS DE-ENERGIZED");
        } else if (!last750 && bus750Live) {
            event("750 kV main bus energized");
        }
        if (last20 && !bus20Live) {
            event("20 kV generator terminal de-energized");
        } else if (!last20 && bus20Live) {
            event("20 kV generator terminal energized");
        }
        if (last110 && !bus110Live) {
            event("110 kV auxiliary supply LOST");
        } else if (!last110 && bus110Live) {
            event("110 kV auxiliary supply restored");
        }
        if (last6A && !bus6ALive) {
            event("6 kV bus A DE-ENERGIZED");
        } else if (!last6A && bus6ALive) {
            event("6 kV bus A energized (" + String.format("%.2f kV", volt6A) + ")");
        }
        if (last6B && !bus6BLive) {
            event("6 kV bus B DE-ENERGIZED");
        } else if (!last6B && bus6BLive) {
            event("6 kV bus B energized");
        }
        if (lastEmergA && !bus6AeLive) {
            event("6 kV emergency bus A DE-ENERGIZED");
        } else if (!lastEmergA && bus6AeLive) {
            event("6 kV emergency bus A supplied by diesel generator");
        }
        if (lastEmergB && !bus6BeLive) {
            event("6 kV emergency bus B DE-ENERGIZED");
        } else if (!lastEmergB && bus6BeLive) {
            event("6 kV emergency bus B supplied by diesel generator");
        }
        if (lastDC && !dcLive) {
            event("220 V DC control power LOST - battery exhausted");
        } else if (!lastDC && dcLive) {
            event("220 V DC control power restored");
        }
        if (gridBreaker && exportMW < -1.0) {
            if (!importAnnounced) {
                importAnnounced = true;
                event("Importing " + String.format("%.0f", -exportMW) + " MW from 750 kV grid");
            }
        } else {
            importAnnounced = false;
        }

        last750 = bus750Live;
        last20 = bus20Live;
        last110 = bus110Live;
        last6A = bus6ALive;
        last6B = bus6BLive;
        lastEmergA = bus6AeLive;
        lastEmergB = bus6BeLive;
        lastDC = dcLive;
    }

    private void event(String text) {
        if (NPPSim.autoControl != null) {
            NPPSim.autoControl.recordEvent(text);
        } else {
            System.out.println("[Grid] " + text);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ================================================================ 操作

    /** 恢复到额定接线（标准初始工况用） */
    void resetToNominal() {
        genBreaker20 = true;
        gridBreaker = true;
        gridBreakerLocked = true;
        line110Breaker = true;
        unitBreakerA = true;
        unitBreakerB = true;
        reserveBreakerA = true;
        reserveBreakerB = true;
        busTie = false;
        emergencyBreakerA = true;
        emergencyBreakerB = true;
        transformer04A = true;
        transformer04B = true;
        chargerOn = true;
        for (int u = 0; u < DIESEL_COUNT; u++) {
            dieselRun[u] = false;
            dieselManual[u] = false;
            dieselOut[u] = 0;
            rpm[u] = 0;
            fuel[u] = 1.0;
            coolant[u] = 25;
            oilTemp[u] = 25;
            exhaust[u] = 25;
            voltDg[u] = 0;
            ampDg[u] = 0;
            hours[u] = 0;
            dieselBreaker[u] = true;
            fuelOutAnnounced[u] = false;
            lubePress[u] = 4.2;
            lubeRunning[u] = 2;
            lubeLowTicks[u] = 0;
            lubeTripAnnounced[u] = false;
        }
        // 润滑油泵全部投入、母管压力按正常值起步
        for (int i = 0; i < lubePumpOn.length; i++) {
            lubePumpOn[i] = true;
        }
        autoStartBlocked[0] = autoStartBlocked[1] = false;
        batterySoc = 1.0;
        deadTicksBus[0] = deadTicksBus[1] = 0;
        last6A = last6B = last110 = lastDC = lastEmergA = lastEmergB = last750 = last20 = true;
        update();
    }

    public boolean isStationPowerOk() {
        return bus6ALive && bus6BLive;
    }

    public boolean isControlPowerOk() {
        return dcLive;
    }

    // ================================================================ 界面读取

    /** Q1 钥匙锁状态（true = 锁定） */
    public boolean isGridBreakerLocked() { return gridBreakerLocked; }

    /** 扭钥匙：切换锁定/解锁 */
    public void toggleGridBreakerLock() {
        gridBreakerLocked = !gridBreakerLocked;
        event("Grid breaker Q1 key " + (gridBreakerLocked ? "LOCKED" : "UNLOCKED"));
    }

    public boolean has20() { return bus20Live; }
    public boolean has750() { return bus750Live; }
    public boolean has110() { return bus110Live; }
    public boolean has6A() { return bus6ALive; }
    public boolean has6B() { return bus6BLive; }
    public boolean has6Ae() { return bus6AeLive; }
    public boolean has6Be() { return bus6BeLive; }
    public boolean has380() { return bus04Live; }
    public boolean hasDC() { return dcLive; }

    public double getGenerationMW() { return generationMW; }
    public double getAuxLoadMW() { return auxLoadMW; }
    public double getExportMW() { return exportMW; }
    public double getFrequencyHz() { return frequencyHz; }

    public double getLoad6A() { return load6A; }
    public double getLoad6B() { return load6B; }
    public double getLoad6Ae() { return load6Ae; }
    public double getLoad6Be() { return load6Be; }
    public double getLoad380() { return load380; }
    public double getLoadDC() { return loadDC; }

    public double getVolt20() { return volt20; }
    public double getVolt750() { return volt750; }
    public double getVolt110() { return volt110; }
    public double getVolt6A() { return volt6A; }
    public double getVolt6B() { return volt6B; }
    public double getVolt6Ae() { return volt6Ae; }
    public double getVolt6Be() { return volt6Be; }
    public double getVolt04() { return volt04; }
    public double getVoltDC() { return voltDC; }

    public double getBatterySoc() { return batterySoc; }

    /** 该段应急母线上柴油机的总出力（0…2，×DIESEL_MW = MW） */
    public double getDieselOutputA() { return dieselOut[0] + dieselOut[1]; }
    public double getDieselOutputB() { return dieselOut[2] + dieselOut[3]; }

    /** 该段母线上柴油机的实际功率 MW */
    public double getDieselBusMW(int bus) {
        double mw = 0;
        for (int u = 0; u < DIESEL_COUNT; u++) {
            if (dieselBus(u) == bus) {
                mw += dieselOut[u] * DIESEL_MW;
            }
        }
        return mw;
    }

    // ---- 柴油机仪表量（unit = 0…3 → DG-A1 / DG-A2 / DG-B1 / DG-B2）----
    public boolean isDieselRunning(int unit) { return dieselRun[unit]; }
    public boolean isDieselBreaker(int unit) { return dieselBreaker[unit]; }
    public double getDieselRpm(int unit) { return rpm[unit]; }
    /** 频率 Hz：4 极机 f = n / 30 */
    public double getDieselFreq(int unit) { return rpm[unit] / 30.0; }
    public double getDieselPowerMW(int unit) { return dieselOut[unit] * DIESEL_MW; }
    public double getDieselFuelPct(int unit) { return fuel[unit] * 100.0; }
    public double getDieselCoolantC(int unit) { return coolant[unit]; }
    public double getDieselOilC(int unit) { return oilTemp[unit]; }
    public double getDieselExhaustC(int unit) { return exhaust[unit]; }
    public double getDieselVoltKV(int unit) { return voltDg[unit]; }
    public double getDieselAmp(int unit) { return ampDg[unit]; }
    public double getDieselHours(int unit) { return hours[unit]; }

    /** 界面/探针操作：启停一台柴油机（手动投入后自动逻辑不再停它） */
    public void setDieselRunning(int unit, boolean run) {
        if (unit < 0 || unit >= DIESEL_COUNT) {
            return;
        }
        if (run && lubePress[unit] < LUBE_START_MIN_BAR) {
            // 润滑油压没建起来不允许起机（真实机组的启动闭锁）
            event("Diesel generator " + dieselTag(unit)
                    + " start blocked - low lube oil pressure");
            return;
        }
        dieselRun[unit] = run;
        dieselManual[unit] = run;
    }

    /** 使某台机出口断路器分/合（分开 = 该机不带应急母线） */
    public void setDieselBreaker(int unit, boolean closed) {
        if (unit >= 0 && unit < DIESEL_COUNT) {
            dieselBreaker[unit] = closed;
        }
    }

    // ---- 润滑油泵（A~E）----

    /** 泵号：0=A→DG-A1，1=B→DG-A2，2=C→DG-B1，3=D→DG-B2，4=E（公用） */
    public boolean isLubePumpOn(int index) {
        return index >= 0 && index < lubePumpOn.length && lubePumpOn[index];
    }

    public void setLubePump(int index, boolean on) {
        if (index < 0 || index >= lubePumpOn.length) {
            return;
        }
        if (lubePumpOn[index] == on) {
            return;
        }
        lubePumpOn[index] = on;
        event("Lube oil pump " + "ABCDE".charAt(index) + (on ? " started" : " stopped"));
    }

    /** 润滑油母管压力 bar（界面数显用） */
    public double getDieselLubePress(int unit) {
        return lubePress[unit];
    }

    /** 该机组运行的润滑油泵台数（本机泵 + 公用泵） */
    public int getDieselLubePumpCount(int unit) {
        return lubeRunning[unit];
    }

    /** 燃油加满（界面"加油"用） */
    public void refuel() {
        for (int u = 0; u < DIESEL_COUNT; u++) {
            fuel[u] = 1.0;
            fuelOutAnnounced[u] = false;
        }
    }
}
