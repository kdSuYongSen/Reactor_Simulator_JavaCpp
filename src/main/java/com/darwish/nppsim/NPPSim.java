package com.darwish.nppsim;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class NPPSim {
    static UI ui;
    //list of all npp components
    static Atmosphere atmosphere;
    static MCC mcc;
    static Core core;
    static ArrayList<SteamValve> sdv_c;
    static ArrayList<SteamValve> sdv_a;
    static ArrayList<SteamValve> msvLoop1;
    static ArrayList<SteamValve> msvLoop2;
    static ArrayList<SteamValve> TG1InletValves;
    static ArrayList<SteamValve> TG2InletValves;
    static ArrayList<Pump> condensate1A; 
    static ArrayList<Pump> condensate2A;
    static ArrayList<Pump> condensate1B;
    static ArrayList<Pump> condensate2B;
    static ArrayList<Pump> mainFeedwaterPumps;
    static ArrayList<Pump> auxFeedwaterPumps;
    static ArrayList<WaterValve> dearatorValves;
    static ArrayList<WaterValve> mainFeederValves;
    static ArrayList<WaterValve> auxFeederValves;
    static ArrayList<Dearator> dearators;
    static ArrayList<Ejector> ejectors;
    /** 真空抽气泵（凝结水页面控制，各作用于一台凝汽器） */
    static VacuumPump vacuumPump1, vacuumPump2;
    static TG tg1, tg2;
    static SteamTables tables;
    static OneWaySteamHeader steamPiping;
    static PressureHeader auxiliaryFWPressureHeader, mainFWPressureHeader, condensateHeader1, condensateHeader2;
    static SimplifiedCondensateHeader condensateHeader;
    static SimpleSuctionHeader fwSuctionHeader;
    static WaterMixer feedwaterMixer1, feedwaterMixer2;
    static AutoControl autoControl;
    static PCS pcs;
    /** 全厂电气系统（220 kV 主电网 / 110 kV 辅助 / 6.6 kV 厂用与应急 / 380 V / 220 V 直流） */
    static ElectricalGrid grid;
    /** 凝结水系统排水阀（凝结水联箱 → 除盐水箱）：手动放水，开阀时界面亮红色"排水阀开启" */
    static WaterValve condensateDrainValve;
    /** 给水系统排水阀（主给水母管 → 除盐水箱）：手动放水，开阀时界面亮红色"排水阀开启" */
    static WaterValve feedwaterDrainValve;
    /** 换热器 A/B（一回路↔二回路热耦合 + 离线冷却河水侧） */
    static ArrayList<HeatExchanger> heatExchangers = new ArrayList<>();

    /**
     * 仅用于自检/调试：≥0 时替代 {@link Core#getThermalPower()} 作为堆芯热功率（MW）。
     * 正常运行为 -1（跟随动力学）。用来在不开棒的情况下验证"堆芯热量 → 汽包 → 二回路"链路。
     */
    static double coreHeatOverrideMW = -1;

    /**
     * 仅用于自检/调试：>0 时按该流量（kg/s，两台合计）给换热器管侧喂给水，
     * 用来在不启动整套给水系统的情况下验证"堆芯功率 → 产汽量"的关系。正常为 0。
     */
    static double feedwaterOverrideKgs = 0;
    static ArrayList<Separator> separators = new ArrayList<>();
    /** 除氧器蒸汽出口联箱：除氧器 → 汽轮机主汽门 / 旁路膨胀阀 / 抽气器 */
    static SteamCollectorHeader deaeratorSteamHeader;
    /** PCS 凝结水过滤段（热阱）：凝结水联箱 → 除氧器水入口 */
    static CondensateFilter pcsCondensateFilter;
    /** 低压加热器 LP-1：凝结水联箱 → 0.15 MPa 抽汽加热 → 过滤段（2026-10-19 新增） */
    static LPHeater lpHeater;
    /** 汽水分离器出口 → 二次侧蒸汽母管的适配器 */
    static SeparatorSteamSource[] separatorSources = new SeparatorSteamSource[2];
    
    /**
     * 汽轮机主汽门 Cv（每台机 2 只，共 4 只）。
     *
     * 定径依据（tools/steam_loop_check/PlantBalance 实测）：
     *   除氧器蒸汽出口联箱 0.61 MPa / 凝汽器 0.0061 MPa，单只阀门
     *   453 kg/s @ Cv=25000；额定工况单只需通过 ~452 kg/s（二回路产汽
     *   ~2255 kg/s，四只均分，已扣除氧器抽汽），故取 2.47e4。
     * 旧值 3044.72（≈53 kg/s/只）是全厂蒸汽直连汽轮机时代的旁路口径，
     * 全厂蒸汽改走除氧器后只够 250 kg/s，汽轮机永远带不上负荷。
     */
    /** 每台汽轮机循环水总量（m³/s）；分给 4 台凝汽器 */
    static final double CONDENSER_CW_M3S = 45.0;

    /**
     * 主汽门 Cv（2026-11-07 按**单回路 6.9 MPa** 重新定径）。
     *
     * <p>实测标定（LegacyFixProbe）：单回路 6.9 MPa 母管下，旧值 9.4e4 在
     * **15 % 开度**就抽走 4 380 kg/s（两台合计），相当于一开阀就甩掉半台机的负荷；
     * 汽包压力被瞬间拉低 1.5 MPa，RBMK 的正空泡反馈把功率在 5 s 内顶到 16 000 MW
     * （额定的 3.4 倍），АР 根本来不及压。旧值是按 4 MPa 母管估的，压差一大流量就失控。</p>
     *
     * <p>按"全开每阀 900 kg/s"（RBMK-1500 单机 1 800 kg/s、两台机 4 只阀）反算：
     * 6.9 MPa 下全开 ≈ 7 300 kg/s/阀 @ Cv=9.4e4 → 要 900 kg/s 需 Cv ≈ 1.16e4，
     * 取 <b>1.2e4</b>。这样 20 % 开度约 1 460 kg/s（两台），是温和的升负荷速度。</p>
     */
    static final double MAIN_STEAM_CV = 1.2e4;
    
    private static Thread updateThread, printThread;
    private static boolean simPaused = false, updating = false, run = true;
    public static boolean core1=false;

    //debug parts here

    //variables for update thread
    private static int updateCount = 0;
    /**
     * 自仿真开始以来的**累计** tick 数（1 tick = 50 ms）。
     *
     * <p>注意 {@link #updateCount} 是 printThread 每 900 ms 清零的"上一窗口内 tick 数"，
     * 不能用来判断仿真跑了多久 —— 无头自检按它等目标步数会永远等不到。
     * 这里另开一个只增不减的计数器。</p>
     */
    public static long totalTicks = 0;
    /** UI 刷新节拍（仿真每 4 tick ≈ 200ms 刷一次界面） */
    private int uiTickCounter = 0;
    //private static boolean updated = false;
    private static int deltaTimeStepLength = 0;
    private static long timeStepLengthCumulative; //IT IS USED DONT BELIEVE THE IDE

    
    /**
     * new simulation instance with default values
     */
    /* ---- 启动分段计时（2026-11-11 性能）：只在 -Dnpp.startup.timing=true 时输出 ---- */
    private static final boolean STARTUP_TIMING = Boolean.getBoolean("npp.startup.timing");
    private static long startupMark;

    private static void startupTiming(String label) {
        if (!STARTUP_TIMING) {
            return;
        }
        long now = System.nanoTime();
        if (startupMark != 0L) {
            System.out.printf("[启动] %-34s %7d ms%n", label, (now - startupMark) / 1_000_000L);
        }
        startupMark = now;
    }

    public NPPSim() {
        startupMark = STARTUP_TIMING ? System.nanoTime() : 0L;
        sdv_c = new ArrayList<>(); 
        sdv_a = new ArrayList<>();
        msvLoop1 = new ArrayList<>(); 
        msvLoop2 = new ArrayList<>();
        TG1InletValves = new ArrayList<>(); 
        TG2InletValves = new ArrayList<>(); 
        condensate1A = new ArrayList<>(); 
        condensate2A = new ArrayList<>();
        condensate1B = new ArrayList<>();
        condensate2B = new ArrayList<>();
        mainFeedwaterPumps = new ArrayList<>();
        auxFeedwaterPumps = new ArrayList<>();
        dearatorValves = new ArrayList<>();
        mainFeederValves = new ArrayList<>();
        auxFeederValves = new ArrayList<>();
        dearators = new ArrayList<>();
        ejectors = new ArrayList<>();

        //parse the steam tables
        tables = Loader.tables;
        startupTiming("蒸汽表 / 字段初始化");
        
        //initialize all components
        atmosphere = new Atmosphere(15);
        core = new Core();
        startupTiming("堆芯 Core（3136 通道）");
        mcc = new MCC();
        startupTiming("一回路 MCC（汽包 + 主泵 + 通道）");

        // 换热器 A/B（一回路→二回路）与汽水分离器 A/B（二次侧）
        // 注意：必须在给水混合器之前创建（混合器直接送水到换热器管侧）
        heatExchangers.clear();
        separators.clear();
        heatExchangers.add(new HeatExchanger("A"));
        heatExchangers.add(new HeatExchanger("B"));
        separators.add(new Separator("A"));
        separators.add(new Separator("B"));
        separatorSources[0] = new SeparatorSteamSource(separators.get(0));
        separatorSources[1] = new SeparatorSteamSource(separators.get(1));
        separators.get(0).setSteamSource(separatorSources[0]);
        separators.get(1).setSteamSource(separatorSources[1]);

        // 二次侧蒸汽母管：由汽水分离器供汽（不再直接接汽包）
        /* 主蒸汽母管（2026-11-07 单回路）：汽包 6.9 MPa 饱和蒸汽直接进汽机。 */
        steamPiping = new OneWaySteamHeader(new Connectable[] {mcc.drum1, mcc.drum2});
        // 除氧器蒸汽出口联箱：全厂蒸汽先经过除氧器，再由这里供汽轮机 / 旁路 / 抽气器
        deaeratorSteamHeader = new SteamCollectorHeader();

        //TG and Condenser Components
        /* 每台汽轮机 4 台凝汽器（共 8 台）。循环水总量不变，4 台各分 1/4：
         * 单台停循环泵只掉 1/4 的排汽面积，真空是渐进的，页面上也能看出是哪一台。 */
        tg1 = new TG(new Condenser[]{
                new Condenser(CONDENSER_CW_M3S / 4), new Condenser(CONDENSER_CW_M3S / 4),
                new Condenser(CONDENSER_CW_M3S / 4), new Condenser(CONDENSER_CW_M3S / 4)});
        tg2 = new TG(new Condenser[]{
                new Condenser(CONDENSER_CW_M3S / 4), new Condenser(CONDENSER_CW_M3S / 4),
                new Condenser(CONDENSER_CW_M3S / 4), new Condenser(CONDENSER_CW_M3S / 4)});
        tg1.setTG1();

        for (int i = 0; i < 4; i++) {
            ejectors.add(new Ejector(5.14, 10, steamPiping, atmosphere, tg1.condenser, atmosphere));
        }
        for (int i = 0; i < 4; i++) {
            ejectors.add(new Ejector(5.14, 10, steamPiping, atmosphere, tg2.condenser, atmosphere));
        }
        vacuumPump1 = new VacuumPump(tg1.condenser);
        vacuumPump2 = new VacuumPump(tg2.condenser);

        /* 主汽门：汽源 = 主蒸汽母管（2026-10-19 改为直供）。
         * 旧接线是"分离器 → 除氧器 → 汽轮机"，全部蒸汽都要过除氧器，
         * 既要 30% 的假抽汽，又把主蒸汽压力降到 0.6 MPa（压力等级白提）。 */
        /* 主汽门全行程 60 s（2026-11-07 单回路）：原来 20 s 全行程时，压力控制器
         * 一发现汽包 6.9 > 设定 6.65 就开阀，5 s 就开到 25 %、抽走 1 000 kg/s，
         * 汽包压力被瞬间拉低 1.5 MPa，触发 RBMK 正空泡把功率顶到 6 400 MW。
         * 真实机组的升负荷曲线本来就慢，60 s 全行程相当于每 5 s 开 8 %。 */
        TG1InletValves.add(new SteamValve(MAIN_STEAM_CV, 60, steamPiping, tg1));
        TG1InletValves.add(new SteamValve(MAIN_STEAM_CV, 60, steamPiping, tg1));
        TG2InletValves.add(new SteamValve(MAIN_STEAM_CV, 60, steamPiping, tg2));
        TG2InletValves.add(new SteamValve(MAIN_STEAM_CV, 60, steamPiping, tg2));

        //add the eight SDV-C valves, 4 per loop（旁路膨胀阀：除氧器出口 → 凝汽器）
        for (int i = 0; i < 4; i++) {
            sdv_c.add(new SteamValve(745.76, 8, steamPiping, tg1.condenser));
        }
        for (int i = 0; i < 4; i++) {
            sdv_c.add(new SteamValve(745.76, 8, steamPiping, tg2.condenser));
        }
        
        //SDV-A
        sdv_a.add(new SteamValve(471.03, 8, mcc.drum1, atmosphere));
        sdv_a.add(new SteamValve(471.03, 8, mcc.drum2, atmosphere));

        //MSV
        msvLoop1.add(new SteamValve(461.33, 8, mcc.drum1, atmosphere)); //group 1
        msvLoop2.add(new SteamValve(461.33, 8, mcc.drum2, atmosphere));
        for (int i = 0; i < 2; i++) {
            msvLoop1.add(new SteamValve(458.53, 8, mcc.drum1, atmosphere)); //group 2
            msvLoop2.add(new SteamValve(458.53, 8, mcc.drum2, atmosphere));
        }
        for (int i = 0; i < 3; i++) {
            msvLoop1.add(new SteamValve(455.48, 8, mcc.drum1, atmosphere)); //group 3
            msvLoop2.add(new SteamValve(455.48, 8, mcc.drum2, atmosphere));
        }
  
        //initialize condensate pumps and dearators  TODO this is greatly simplified needs reworking
        condensateHeader = new SimplifiedCondensateHeader();
        condensateHeader1 = new PressureHeader();
        condensateHeader2 = new PressureHeader();
        // PCS 凝结水过滤段（热阱）：凝结水联箱 → 除氧器水入口；排污送除盐水箱（PCS 建好后接上）
        /* 二回路凝结水流程（与设计拓扑一致）：
         *   凝汽器 → 凝结水泵 → 凝结水联箱 → **精处理(CCPU)** → **低压加热器 LP-1** → 除氧器
         * 精处理必须放在水温最低处：混合床树脂长期工作温度上限约 60 °C，
         * 放到加热器后面（104 °C）会把树脂烫坏。 */
        pcsCondensateFilter = new CondensateFilter(condensateHeader, null);
        lpHeater = new LPHeater(
                new Connectable[]{tg1.lpExtractionPort, tg2.lpExtractionPort},
                null,                // 疏水去向在除氧器建好之后补（见下方 setDrains）
                pcsCondensateFilter);
        for (int i = 0; i < 3; i++) {
            condensate1A.add(new Pump(740, 0.95f, 1.117, 0.022555295, 30, 40, 167, tg1.condenser, condensateHeader1));
        }
        for (int i = 0; i < 3; i++) {
            condensate2A.add(new Pump(740, 0.95f, 1.117, 0.022555295, 30, 40, 167, tg2.condenser, condensateHeader2));
        }
        for (int i = 0; i < 3; i++) {
            condensate1B.add(new Pump(2975, 0.95f, 1.766, 0.2157463, 30, 40, 267, condensateHeader1, condensateHeader));
        }
        for (int i = 0; i < 3; i++) {
            condensate2B.add(new Pump(2975, 0.95f, 1.766, 0.2157463, 30, 40, 267, condensateHeader2, condensateHeader));
        }
        condensateHeader1.setSources(new Pump[] {condensate1A.get(0), condensate1A.get(1), condensate1A.get(2)});
        condensateHeader2.setSources(new Pump[] {condensate2A.get(0), condensate2A.get(1), condensate2A.get(2)});
        condensateHeader.setSources(new Pump[] {condensate1B.get(0), condensate1B.get(1), condensate1B.get(2), condensate2B.get(0), condensate2B.get(1), condensate2B.get(2)});
        for (int i = 0; i < 4; i++) {
            /* 抽汽式：0/1 号接 TG-1 的抽汽口，2/3 号接 TG-2；余汽排各自的凝汽器。 */
            dearators.add(new Dearator(i,
                    i < 2 ? tg1.extractionPort : tg2.extractionPort,
                    i < 2 ? tg1.condenser : tg2.condenser));
            // 除氧器水入口：水来自 PCS 凝结水过滤段（热阱），不再直接取自凝结水联箱
            /* 除氧器进水阀 Cv（2026-11-04 缩小）：原 1667 在 LP 加热器与除氧器 0.53 MPa
             * 的压差下全开能灌进约 2,800 kg/s（单台），是额定给水量的 5 倍多。
             * 水位控制只有"开/关"两档，一开就洪水式进水 —— 面板上就是"莫名其妙一直进水"。
             * 按单台额定 ~500 kg/s、留 40 % 余量重定径：Cv 420 ≈ 全开 700 kg/s。 */
            dearatorValves.add(new WaterValve(420.0, 12, lpHeater, dearators.get(i)));
        }
        // 低压加热器疏水 → 4 台除氧器（此时除氧器才建好）
        if (lpHeater != null) {
            lpHeater.setDrains(dearators.toArray(new Connectable[0]));
        }
        // 除氧器蒸汽出口联箱的源 = 4 台除氧器（除氧器必须在上面建完之后才能接）
        deaeratorSteamHeader.setSources(new Connectable[] {
            dearators.get(0), dearators.get(1), dearators.get(2), dearators.get(3)
        });
        
        //initialize feedwater pumps and piping/valves
        // 给水 → 换热器管侧（不再是直接进汽包；汽包水位由换热器凝结水回注维持）
        /* 给水直接进汽包（单回路） */
        feedwaterMixer1 = new WaterMixer(mcc.drum1, 2);
        feedwaterMixer2 = new WaterMixer(mcc.drum2, 2);
        
        //auxiliaryFWSuctionHeader = new SimpleSuctionHeader(new Connectable[] {dearators.get(0), dearators.get(1), dearators.get(2), dearators.get(3)});
        fwSuctionHeader = new SimpleSuctionHeader(new Connectable[] {dearators.get(0), dearators.get(1), dearators.get(2), dearators.get(3)}, 1);
        // 4 台除氧器并联供水：隔离阀全部打开，按阀位均分给水需求。
        // 旧实现只打开第 1 台，全部给水都从 1 号除氧器抽取 —— 1 号被抽成负质量
        // （随后被 sanitize 钳掉，质量凭空消失），2~4 号却因只进不出而水位上涨、
        // 经除氧器溢流阀排到除盐水箱，整条给水回路的水就这么"漏"光了。
        fwSuctionHeader.isolationValveArray.forEach(valve -> valve.setPosition(1.0f));
        auxiliaryFWPressureHeader = new PressureHeader();
        mainFWPressureHeader = new PressureHeader();
        for (int i = 0; i < 7; i++) {
            mainFeedwaterPumps.add(new Pump(2982, 0.95f, 8.9, 0.14709975, 30, 40, 833, fwSuctionHeader, mainFWPressureHeader)); //9.5
        }
        for (int i = 0; i < 6; i++) {
            auxFeedwaterPumps.add(new Pump(2970, 0.15f, 8.6, 0.08825985, 30, 40, 133, fwSuctionHeader, auxiliaryFWPressureHeader)); //9.1
        } 
        mainFWPressureHeader.setSources(new Pump[] {mainFeedwaterPumps.get(0), mainFeedwaterPumps.get(1), mainFeedwaterPumps.get(2), mainFeedwaterPumps.get(3), mainFeedwaterPumps.get(4), mainFeedwaterPumps.get(5), mainFeedwaterPumps.get(6)});
        auxiliaryFWPressureHeader.setSources(new Pump[] {auxFeedwaterPumps.get(0), auxFeedwaterPumps.get(1), auxFeedwaterPumps.get(2), auxFeedwaterPumps.get(3), auxFeedwaterPumps.get(4), auxFeedwaterPumps.get(5)});
        for (int i = 0; i < 3; i++) {
            mainFeederValves.add(new WaterValve(498.01, 40, mainFWPressureHeader, feedwaterMixer1));
        }
        for (int i = 0; i < 3; i++) {
            mainFeederValves.add(new WaterValve(498.01, 40, mainFWPressureHeader, feedwaterMixer2));
        }
        auxFeederValves.add(new WaterValve(197.44, 40, auxiliaryFWPressureHeader, feedwaterMixer1));
        auxFeederValves.add(new WaterValve(197.44, 40, auxiliaryFWPressureHeader, feedwaterMixer2));
        
        //PCS
        pcs = new PCS();
        startupTiming("二回路（汽机/凝汽器/除氧器/给水/PCS）");
        // PCS 凝结水过滤段的排污送除盐水箱
        pcsCondensateFilter.setBlowdownSink(pcs.demineralizedWaterTank);

        /* 凝结水 / 给水系统排水阀（2026-10-28 新增）。
         * 这两个是**手动放水阀**（检修/水质不合格时排放），默认关闭：
         *   · 凝结水联箱 → 除盐水箱
         *   · 主给水母管 → 除盐水箱
         * 开阀时对应窗口亮红色"排水阀开启"报警，提醒有人在放水。 */
        condensateDrainValve = new WaterValve(200.0, 30, condensateHeader, pcs.demineralizedWaterTank);
        feedwaterDrainValve = new WaterValve(120.0, 40, mainFWPressureHeader, pcs.demineralizedWaterTank);

        // MCP A/B 默认开启（环路 1/2 各一台）：400 RPM（额定 1000 的 40%）、节流阀 70%
        mcc.mcp.get(0).setActive(true);
        mcc.mcp.get(4).setActive(true);
        mcc.mcp.get(0).rpmSetting = 0.4f;
        mcc.mcp.get(4).rpmSetting = 0.4f;
        /* 8 台主循环泵的出口阀统一 70% 开度。
         * 原来只给 0/4 号（每环路第一台）设了 0.7，其余 6 台是 0%：
         * 操作员启动第 2/3/4 台泵时"泵转了但没流量"，看起来像阀门初始化坏了。
         * 主循环泵没有单独的流量控制回路，出口阀就是节流件，统一开度才一致。 */
        for (Pump pump : mcc.mcp) {
            if (pump != null && pump.dischargeValve != null) {
                pump.dischargeValve.setPosition(0.7f);
            }
        }
        mcc.mcp.get(0).dischargeValve.setAutoState(1);   // 阀位保持 70%（不被自动开阀推到全开）
        mcc.mcp.get(4).dischargeValve.setAutoState(1);

        // 换热器/汽水分离器已在上面创建（二次侧回路）

        //凝汽器排水阀：把热井多余的水排到除盐水箱（水位过高时自动开启，见 Condenser.update）
        tg1.condenser.attachDrain(pcs.demineralizedWaterTank);
        tg2.condenser.attachDrain(pcs.demineralizedWaterTank);
        // 应急排水目标：热井水位 >150% 时把多余水量转入除盐水箱（防止数值溢出）
        tg1.condenser.setReliefTarget(pcs.demineralizedWaterTank);
        tg2.condenser.setReliefTarget(pcs.demineralizedWaterTank);

        // 汽水分离器泄压阀：高压力/高水位自动开阀，排向除盐水箱（后续改抑压水池）
        if (separators != null) {
            for (Separator sep : separators) {
                if (sep != null) {
                    sep.setReliefTarget(pcs.demineralizedWaterTank);
                }
            }
        }
        
        //initialize the automatic control system
        autoControl = new AutoControl();
        /* 冷启动默认投运：除氧器/给水/热井水位自动 + 自动 AZ（2026-11-17）。
         * 只投开关、不改设备状态 —— 堆芯仍是全插棒 0 MW、泵仍停、汽机仍跳闸。
         * 标准工况（StandardIC）随后会覆盖成运行态的整定。 */
        autoControl.applyColdStartDefaults();
        startupTiming("自动控制 AutoControl");
        /* 电气系统：放在所有泵/汽机之后建，第一次 update 就能读到真实负荷 */
        grid = new ElectricalGrid();
        grid.resetToNominal();

        //set variables if needed
//        core.coreArray.forEach(row -> {
//            row.forEach(channel -> {
//                channel.setNeutronCount(new Double[] {1e9, 0.0});
//                if(channel instanceof ControlRodChannel) {
//                    if (channel instanceof FASRChannel) {
//                        ((ControlRodChannel) channel).setPosition(0.0f);
//                    } else {
//                        ((ControlRodChannel) channel).setPosition(0.65f);
//                    }
//                }
//            });
//        });
//        autoControl.az1Control.setEnabled(false);
//        mcc.setWaterTemp(280);
//        mcc.mcp.get(0).setActive(true);
//        mcc.mcp.get(1).setActive(true);
//        mcc.mcp.get(4).setActive(true);
//        mcc.mcp.get(5).setActive(true);
//        mcc.mcp.get(0).setRPM(1000);
//        mcc.mcp.get(1).setRPM(1000);
//        mcc.mcp.get(4).setRPM(1000);
//        mcc.mcp.get(5).setRPM(1000);
//        autoControl.automaticRodController.setSetpoint(500);
//        dearators.forEach(dearator -> {
//            dearator.setWaterTemp(190);
//        });
        

        //initialize UI
        simPaused = false;
        ui = new UI();
        startupTiming("界面 UI");

        //start simulation
        run = true;
        updateTimer();
        printTimer();
    }
    
    /**
     * 从 v2 存档的 {@link PlantState} 构造仿真。
     *
     * 新的存档路径：直接按字段恢复设备对象图，不再依赖 stateArray 的位置索引。
     */
    public NPPSim(PlantState plant, SaveGame game) {
        if (plant == null) {
            throw new IllegalArgumentException("PlantState is null");
        }
        tables = Loader.tables;

        atmosphere = plant.atmosphere;
        core = plant.core;
        mcc = plant.mcc;

        sdv_c = orEmpty(plant.sdv_c);
        sdv_a = orEmpty(plant.sdv_a);
        msvLoop1 = orEmpty(plant.msvLoop1);
        msvLoop2 = orEmpty(plant.msvLoop2);
        TG1InletValves = orEmpty(plant.TG1InletValves);
        TG2InletValves = orEmpty(plant.TG2InletValves);

        condensate1A = orEmpty(plant.condensate1A);
        condensate2A = orEmpty(plant.condensate2A);
        condensate1B = orEmpty(plant.condensate1B);
        condensate2B = orEmpty(plant.condensate2B);
        mainFeedwaterPumps = orEmpty(plant.mainFeedwaterPumps);
        auxFeedwaterPumps = orEmpty(plant.auxFeedwaterPumps);

        dearatorValves = orEmpty(plant.dearatorValves);
        mainFeederValves = orEmpty(plant.mainFeederValves);
        auxFeederValves = orEmpty(plant.auxFeederValves);

        dearators = orEmpty(plant.dearators);
        ejectors = orEmpty(plant.ejectors);
        vacuumPump1 = plant.vacuumPump1;
        vacuumPump2 = plant.vacuumPump2;

        tg1 = plant.tg1;
        tg2 = plant.tg2;

        steamPiping = plant.steamPiping;
        auxiliaryFWPressureHeader = plant.auxiliaryFWPressureHeader;
        mainFWPressureHeader = plant.mainFWPressureHeader;
        condensateHeader1 = plant.condensateHeader1;
        condensateHeader2 = plant.condensateHeader2;
        condensateHeader = plant.condensateHeader;
        fwSuctionHeader = plant.fwSuctionHeader;
        feedwaterMixer1 = plant.feedwaterMixer1;
        feedwaterMixer2 = plant.feedwaterMixer2;

        autoControl = plant.autoControl;
        pcs = plant.pcs;
        /* 电气系统：新存档带完整状态；旧存档没有这个字段（null）→ 按额定接线补建 */
        grid = plant.grid != null ? plant.grid : new ElectricalGrid();

        heatExchangers = orEmpty(plant.heatExchangers);
        separators = orEmpty(plant.separators);
        separatorSources = plant.separatorSources;
        deaeratorSteamHeader = plant.deaeratorSteamHeader;
        pcsCondensateFilter = plant.pcsCondensateFilter;
        lpHeater = plant.lpHeater;

        if (separatorSources == null) {
            separatorSources = new SeparatorSteamSource[separators.size()];
            for (int i = 0; i < separators.size(); i++) {
                separatorSources[i] = new SeparatorSteamSource(separators.get(i));
            }
        }
        if (deaeratorSteamHeader == null && !dearators.isEmpty()
                && dearators.get(0).steamOutlet.drain instanceof SteamCollectorHeader) {
            deaeratorSteamHeader = (SteamCollectorHeader) dearators.get(0).steamOutlet.drain;
        }
        if (pcsCondensateFilter == null && !dearatorValves.isEmpty()
                && dearatorValves.get(0).source instanceof CondensateFilter) {
            pcsCondensateFilter = (CondensateFilter) dearatorValves.get(0).source;
        }
        if (pcsCondensateFilter == null && !dearatorValves.isEmpty()
                && dearatorValves.get(0).source instanceof LPHeater) {
            // 新接线：阀门源是低压加热器，精处理段在它的上游
            lpHeater = (LPHeater) dearatorValves.get(0).source;
            if (lpHeater.getUpstream() instanceof CondensateFilter) {
                pcsCondensateFilter = (CondensateFilter) lpHeater.getUpstream();
            }
        }

        relinkPlant();

        if (core != null && game != null && game.nativeCoreState != null) {
            if (core.restoreNativeState(game.nativeCoreState)) {
                core.restoreInputs(game.coreInputs);
                System.out.println("[Save] 堆芯状态恢复: " + core.getThermalPower()
                        + " MW, 棒位=" + core.getRodInsertion()
                        + ", 氙=" + core.getXenonWorthPcm() + " pcm");
            } else {
                System.err.println("[Save] 物理内核不支持状态恢复，堆芯按停堆态载入");
            }
        } else {
            System.err.println("[Save] 存档不含 native 堆芯状态，堆芯按停堆态载入");
        }

        simPaused = false;
        ui = new UI();
        run = true;
        updateTimer();
        printTimer();
    }

    private static <T> ArrayList<T> orEmpty(ArrayList<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    /** 重新连接 transient 字段（Separator.steamSource）以及读档后可能失效的静态别名。 */
    private void relinkPlant() {
        if (steamPiping != null) {
            steamPiping.sources = new Connectable[] {mcc.drum1, mcc.drum2};
        }
        for (int i = 0; i < separators.size() && i < separatorSources.length; i++) {
            separators.get(i).setSteamSource(separatorSources[i]);
        }
        if (feedwaterMixer1 != null && heatExchangers.size() > 0) {
            feedwaterMixer1.drain = mcc.drum1;
        }
        if (feedwaterMixer2 != null && heatExchangers.size() > 1) {
            feedwaterMixer2.drain = mcc.drum2;
        }
        if (deaeratorSteamHeader != null) {
            deaeratorSteamHeader.setSources(dearators.toArray(new Connectable[0]));
        }
        if (pcsCondensateFilter != null) {
            if (condensateHeader != null) {
                pcsCondensateFilter.setUpstream(condensateHeader);
            }
            if (pcs != null && pcs.demineralizedWaterTank != null) {
                pcsCondensateFilter.setBlowdownSink(pcs.demineralizedWaterTank);
            }
        }
        /* 低压加热器是流过式的（没有自己的水库存），读档时重接上游即可：
         * 它位于精处理之后、除氧器之前。 */
        if (lpHeater != null) {
            lpHeater.setUpstream(pcsCondensateFilter != null ? pcsCondensateFilter : condensateHeader);
            if (dearators != null) {
                lpHeater.setDrains(dearators.toArray(new Connectable[0]));
            }
        }
    }
    private void updateTimer() {
        updateThread = new Thread(() -> {
            try {
                while(run) {
                    while (simPaused) {
                        updating = false;
                        Thread.sleep(100);
                    }
                    updating = true;
                    long start = System.nanoTime();
                    //all simulation components's update methods go here. 
                    //RULE OF THUMB: objects from "Components" file should be updated before their sources/drains if possible
                    sdv_c.forEach(valve -> {
                        valve.update();
                    });
                    sdv_a.forEach(valve -> {
                        valve.update();
                    });
                    msvLoop1.forEach(valve -> {
                        valve.update();
                    });
                    msvLoop2.forEach(valve -> {
                        valve.update();
                    });
                    
                    TG1InletValves.forEach(valve -> {
                        valve.update();
                    });
                    TG2InletValves.forEach(valve -> {
                        valve.update();
                    });
                    NPPSim.dearators.forEach(dearator -> {
                        dearator.steamInlet.update();
                        dearator.steamOutlet.update();
                    });
                    steamPiping.update();
                    TG.updateGridPhase(); //static variable of class TG updated
                    tg1.update();
                    tg2.update();
                    /* 电气系统：按本 tick 的发电机出力与各泵电流算厂用电/上网功率，
                     * 母线失电时在这里做联锁（跳泵、柴油机自启、直流转蓄电池）。 */
                    grid.update();
                    ejectors.forEach(ejector -> {
                        ejector.update();
                    });
                    vacuumPump1.update();
                    vacuumPump2.update();
                    tg1.condenser.update();
                    tg2.condenser.update();
                    
                    dearatorValves.forEach(valve -> {
                        valve.update();
                    });
                    // PCS 凝结水过滤段（精处理）：按上游登记的取水量向凝结水联箱要水
                    // （必须排在 condensateHeader.update() 之前，需求才会被分摊给凝结水泵）
                    pcsCondensateFilter.update();
                    // 低压加热器：把精处理后的凝结水加热到 ~104 °C 再送除氧器
                    if (lpHeater != null) {
                        lpHeater.update();
                    }

                    condensateHeader.update();
                    
                    condensate1B.forEach(pump -> {
                        pump.update();
                    });
                    condensate2B.forEach(pump -> {
                        pump.update();
                    });
                    
                    condensateHeader1.update();
                    // 凝结水 / 给水系统排水阀：**自动**排水（超压自动泄放），
                    // 手动指令优先（面板"开/关"优先于 autoState）。
                    if (condensateDrainValve != null) {
                        if (condensateDrainValve.isAutoEnabled()) {
                            double p = condensateHeader.getPressure();
                            if (p > 1.20) {
                                condensateDrainValve.setAutoState(2);
                            } else if (p < 1.00) {
                                condensateDrainValve.setAutoState(0);
                            } else {
                                condensateDrainValve.setAutoState(1);
                            }
                        }
                        condensateDrainValve.update();
                    }
                    if (feedwaterDrainValve != null) {
                        if (feedwaterDrainValve.isAutoEnabled()) {
                            double p = mainFWPressureHeader.getPressure();
                            if (p > 7.50) {
                                feedwaterDrainValve.setAutoState(2);
                            } else if (p < 7.20) {
                                feedwaterDrainValve.setAutoState(0);
                            } else {
                                feedwaterDrainValve.setAutoState(1);
                            }
                        }
                        feedwaterDrainValve.update();
                    }
                    condensateHeader2.update();
                    //System.out.println(condensateHeader.pressure + " " + condensateHeader1.pressure);
                    
                    condensate1A.forEach(pump -> {
                        pump.update();
                    });
                    condensate2A.forEach(pump -> {
                        pump.update();
                    });
                    
                    feedwaterMixer1.update();
                    feedwaterMixer2.update();
                    mainFeederValves.forEach(valve -> {
                        valve.update();
                    });
                    auxFeederValves.forEach(valve -> {
                        valve.update();
                    });
                    mainFWPressureHeader.update();
                    auxiliaryFWPressureHeader.update();
                    mainFeedwaterPumps.forEach(pump -> {
                        pump.update();
                    });
                    auxFeedwaterPumps.forEach(pump -> {
                        pump.update();
                    });
                    
                    fwSuctionHeader.update();
                    
                    dearators.forEach(dearator -> {
                        dearator.update();
                    });
                    // 除氧器 → 汽轮机/旁路/抽气器 的汇管。
                    // 必须排在除氧器 update 之后：除氧器在 update 里按实际可供量回调
                    // adjustInflow()，汇管随后结算才能拿到修正后的进出汽量。
                    // 压力由各除氧器 getPressure() 给出，供下一 tick 的主汽门使用。
                    deaeratorSteamHeader.update();
                    
                    pcs.update();

                    NPPSim.core1 = true; //this value is read by the MTK so it won't update while the core is updating
                    core.update();
                    NPPSim.core1 = false;

                    /* ---- 堆芯 → 一回路热的唯一路径（2026-11-01 修正）----
                     * 堆芯功率由 Core 下发给每一根燃料通道，通道自己完成"水升温 → 蒸发 → 送汽包"，
                     * 汽包只做汽水分离（与真实 RBMK 的鼓式分离器一致）。
                     *
                     * 2026-10-27 的写法是：堆芯功率既让通道加热（Channels.java 里 thermalPower*50 kJ），
                     * 又在这里把同一份 Core.thermalPower 均分注进两台汽包 —— 同一份 4800 MW 计了两次。
                     * 后果是产汽量约 2 倍、汽包水位持续上涨、最后走泄压阀/排污，表现为"物质缓慢消失"。
                     * 自检用的 coreHeatOverrideMW 改为在 Core.update() 内部替代堆芯功率，语义不变。 */
                    mcc.update();

                    /* ---- 单回路（2026-11-07）：汽包蒸汽直接进母管，给水直接回汽包。 */
                    double mcpFlowKgs = 0;
                    for (Pump p : mcc.mcp) {
                        mcpFlowKgs += p.getFlowRate();
                    }
                    autoControl.update();
                    //end of components to update
                    updateCount++;
                    totalTicks++;
                    long end = System.nanoTime();
                    int timestep = (int)(end - start);
                    deltaTimeStepLength = (50000000 - timestep);
                    int deltaMillis = deltaTimeStepLength / 1000000;
                    int deltaNanos = deltaTimeStepLength - deltaMillis * 1000000;
                    if (deltaTimeStepLength > 0) {
                        updating = false;
                        Thread.sleep(deltaMillis, deltaNanos);
                        updating = true;
                        timeStepLengthCumulative += deltaTimeStepLength;
                    }
                    timeStepLengthCumulative += timestep;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                /* 无头自检（tools/dev/*Check）里没有窗口可看，必须把栈打到 stderr，
                 * 否则仿真线程静默死掉、界面停在最后一帧，看起来像"模型不动作"。 */
                e.printStackTrace();
                new ErrorWindow("A fatal error occured", ExceptionUtils.getStackTrace(e), false).setVisible(true);
            }
        });
        updateThread.start();
    }

    private void printTimer() {
        final long timeToSleep[] = {900};
        printThread = new Thread(() -> {
            try {
                while(run) {
                    Thread.sleep(timeToSleep[0]);
                    while (updateCount < 20 ) {
                        Thread.sleep(10);
                    }
                    if (timeStepLengthCumulative / updateCount > 50000000) {
                        System.out.println("WARNING: Average timeStep = " + timeStepLengthCumulative / updateCount);
                    }
                    //System.out.println("Average time step: " + timeStepLengthCumulative / updateCount + " " + updateCount); //used sometimes for debugging
                    if (updateCount > 20) {
                        if (timeToSleep[0] > 500) {
                            timeToSleep[0] -= 50;
                        }
                        System.out.println("WARNING: Timesteps = " + updateCount + ". UI Wait time will be " + timeToSleep[0]);
                    }
                    updateCount = 0;
                    timeStepLengthCumulative = 0;
                    autoControl.updateSimulationTime();
                    /* 单帧界面刷新的异常不能打死这个节拍线程（2026-11-11）：
                     * 这里一抛，printThread 就退出，之后所有窗口的数值冻结在最后一帧，
                     * 但仿真还在跑 —— 看起来像"仿真不动了"，实际是刷新线程没了。 */
                    try {
                        ui.update();
                        // 各泵窗口的槽式拨杆跟随模型（含失电/低油压联锁跳泵）
                        javax.swing.SwingUtilities.invokeLater(PumpSwitchPanel::syncAll);
                    } catch (Exception uiError) {
                        uiError.printStackTrace();
                    }
                    while (simPaused) {
                        Thread.sleep(100);
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        printThread.start();
    }
    
    public static void endSimulation() {
        try {
            for (int i = 0; i < UI.uiThreads.size(); i++) {
                UI.uiThreads.get(i).interrupt();
            }
            UI.uiThreads.clear();
            ui.setVisible(false);
            updateThread.interrupt();
            printThread.interrupt();
            run = false;
            ui.dispose();
            Loader.soundProvider.stopAll();
        } catch (Exception e) {
            //void
        }
    }
    
    public static void setPaused(boolean paused) {
        simPaused = paused;
    }
    
    public static boolean isPaused() {
        return simPaused;
    }
    
    public static boolean isRunning() {
        return run;
    }
    
    public static void save(File file) {
        boolean wasPaused = isPaused();
        try {
            FileOutputStream fout = new FileOutputStream(file);
            ObjectOutputStream oos = new ObjectOutputStream(fout);
            setPaused(true);
            while(updating) {
                Thread.sleep(5);
            }
            SaveGame game = new SaveGame();
            game.plant = PlantState.capture();
            game.nativeCoreState = core != null ? core.captureNativeState() : null;
            game.coreInputs = core != null ? core.captureInputs() : null;
            if (core != null) {
                System.out.println("[Save] 保存堆芯: " + core.getThermalPower()
                        + " MW, 棒位=" + core.getRodInsertion()
                        + ", 氙=" + core.getXenonWorthPcm() + " pcm");
            }
            if (game.nativeCoreState == null) {
                System.out.println("[Save] 警告：当前物理内核不支持状态导出，堆芯将无法恢复");
            }
            oos.writeObject(game);
            setPaused(wasPaused);
            oos.flush();
            fout.flush();
            oos.close();
            fout.close();
        } catch (Exception e) {
            new ErrorWindow("Error Saving IC", ExceptionUtils.getStackTrace(e), true).setVisible(true);
            setPaused(wasPaused);
        }
    }
    
    public static void load(File file) {
        if (Loader.loader != null) {
            Loader.loader.setLoading(false);
        }
        try (
         FileInputStream fin = new FileInputStream(file);
         ObjectInputStream ois = new ObjectInputStream(fin);          
        ) {
            Object loaded = ois.readObject();
            if (!(loaded instanceof SaveGame)) {
                if (loaded instanceof List) {
                    throw new java.io.IOException(
                            "旧版存档（无版本号，stateArray 位置索引）无法可靠恢复；请用当前版本新开一局并重新保存");
                }
                throw new java.io.IOException("无法识别的存档格式："
                        + (loaded == null ? "null" : loaded.getClass().getName()));
            }
            SaveGame game = (SaveGame) loaded;
            if (game.version > SaveGame.CURRENT_VERSION) {
                throw new java.io.IOException("存档版本 " + game.version
                        + " 高于当前程序支持的 " + SaveGame.CURRENT_VERSION
                        + "，请升级模拟器后再读取");
            }
            if (game.version != SaveGame.CURRENT_VERSION || game.plant == null) {
                throw new java.io.IOException("旧版存档（v" + game.version
                        + "）使用已废弃的位置索引格式，无法可靠恢复；请用当前版本新开一局并重新保存");
            }
            ois.close();
            fin.close();
            endSimulation();
            Loader.simulation = new NPPSim(game.plant, game);
        } catch (Exception e) {
            e.printStackTrace();
            new ErrorWindow("Error Loading IC", ExceptionUtils.getStackTrace(e), true).setVisible(true);
        }
    }
}
