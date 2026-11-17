package com.darwish.nppsim;

import static com.darwish.nppsim.Loader.soundProvider;
import static com.darwish.nppsim.NPPSim.TG1InletValves;
import static com.darwish.nppsim.NPPSim.TG2InletValves;
import static com.darwish.nppsim.NPPSim.auxFeederValves;
import static com.darwish.nppsim.NPPSim.condensate1A;
import static com.darwish.nppsim.NPPSim.condensate1B;
import static com.darwish.nppsim.NPPSim.condensate2A;
import static com.darwish.nppsim.NPPSim.condensate2B;
import static com.darwish.nppsim.NPPSim.core;
import static com.darwish.nppsim.NPPSim.dearatorValves;
import static com.darwish.nppsim.NPPSim.mainFeederValves;
import static com.darwish.nppsim.NPPSim.msvLoop1;
import static com.darwish.nppsim.NPPSim.msvLoop2;
import static com.darwish.nppsim.NPPSim.pcs;
import static com.darwish.nppsim.NPPSim.sdv_a;
import static com.darwish.nppsim.NPPSim.sdv_c;
import java.io.Serializable;
import java.util.ArrayList;
import static com.darwish.nppsim.NPPSim.mcc;
import static com.darwish.nppsim.NPPSim.tg1;
import static com.darwish.nppsim.NPPSim.tg2;
import java.util.Arrays;

public class AutoControl extends Component {
    ArrayList<String> eventLog = new ArrayList<>();
    ArrayList<OutflowWaterLevelControl> condenserWaterLevelControl = new ArrayList<>();
    ArrayList<InflowWaterLevelControl> dearatorWaterControl = new ArrayList<>();
    ArrayList<InflowWaterLevelControl> dearatorMakeupControl = new ArrayList<>();
    ArrayList<InflowWaterLevelControl> dearatorWaterAndMakeupControl = new ArrayList<>();
    ArrayList<InletSteamPressureControl> dearatorPressureControl = new ArrayList<>();
    ArrayList<InflowWaterLevelControl> auxFeederControl = new ArrayList<>();
    ArrayList<InflowWaterLevelControl> mainFeederControl = new ArrayList<>();
    /**
     * 给水三冲量（2026-11-07）：水位（主）+ 蒸汽流量（前馈）+ 给水流量（反馈）。
     *
     * <p>双回路里给水阀控的是换热器管侧水位，而水位是"进水 − 蒸发"的积分量：
     * 水位一平衡，给水就停在"刚好等于蒸发量"的低流量上（实测 446 kg/s），
     * 二次侧产汽也就上不去，主汽门 100 % 仍抽不到汽，最后汽机逆功率。
     * 三冲量把蒸汽流量直接前馈进给水设定，给水不再等水位反应。</p>
     */
    ArrayList<FeedwaterThreeElement> feedwaterControl = new ArrayList<>();
    /** 给水是否由三冲量接管（false 时退回单冲量 PI） */
    public boolean feedwaterAuto = false;
    ArrayList<OutletSteamPressureControl> sdv_cControl = new ArrayList<>();
    ArrayList<OutletSteamPressureControl> sdv_aControl = new ArrayList<>();
    ArrayList<OutletSteamPressureControl> tgValveControl = new ArrayList<>();
    ArrayList<OutletSteamPressureControl> msv1Control = new ArrayList<>();
    ArrayList<OutletSteamPressureControl> msv2Control = new ArrayList<>();
    AZ1Control az1Control;
    /** 自动 AZ 总开关（反应堆窗口离线冷却面板下方的开关）：关闭后 AZ-1~AZ-5 不再自动跳闸 SCRAM。
     *  UI 在 EDT 上写、仿真线程在读，因此用 volatile 保证关闭动作立刻对仿真可见。 */
    public volatile boolean azAutoEnabled = true;

    /* ==================== AZ 保护：分层逻辑（2026-11-03 重写） ====================
     *
     * 真实的 RBMK 里 АЗ 不是"一到功率就全插"的一刀切，而是一组**各自独立**的保护信号，
     * 每个盯自己的被测量、有自己的定值，动作后果也分轻重：
     *
     *   保护    监测量                     定值                          动作（本模型）
     *   AZ-1   一回路（分离器）压力         > 7.26 MPa                    灯 + 记录；持续 8 s 升级 AZ-1K
     *   AZ-2   热功率                       > 106%（5088 MW）             灯 + 记录 + АР 设定降到 98%；持续 6 s 升级
     *   AZ-3   热功率                       > 110%（5280 MW）             灯 + 记录 + АР 设定降到 90%、主汽门关小；持续 3 s 升级
     *   AZ-4   分离器水位                   > +30 % / < −40 %             灯 + 记录 + 给水联锁；持续 10 s 升级
     *   AZ-5   手动                         —                             全棒插入（紧急停堆按钮）
     *   AZ-6   主蒸汽母管压力               < 2.0 MPa（且堆在运行）        灯 + 记录 + 两台汽机脱扣
     *   AZ-1K  周期 / 反应性 / 功率 / 压力   < 10 s / > 0.02 / > 120% / > 7.50 MPa / 水位 < −60 %
     *          快速功率保护（按钮 + 自动）  —                              **全棒快速插入**
     *   BSM    快速事故保护                 AZ-1K 后 3 s 仍 > 120% / 压力 > 7.60 / 水位 < −70
     *          （按钮 + 自动）              —                              **全棒 + BSM 棒快插**
     *
     * 关键点（按用户要求）：**只有 AZ-1K 和 BSM 会自动动棒**。AZ-1~AZ-4、AZ-6 只报警和联锁，
     * 工况持续恶化才升级到 AZ-1K——避免"功率刚到 108% 全堆棒就一头砸下去"这种过度反应。
     */
    static final double RATED_POWER_MW = 4800.0;
    /** AZ-1：一回路压力高 */
    /**
     * AZ-1 汽包压力定值 MPa（2026-11-07 由 7.26 提到 7.40）。
     *
     * <p>安全阀 SDV-c 在 6.98 MPa 就该开（8 只阀泄到凝汽器），AZ-1 只是它的后备。
     * 原来 7.26 只比安全阀高 0.28 MPa，负荷瞬态里汽包很容易碰到 7.26、走 8 s 升级
     * 到 AZ-1K 把整堆插掉（实测 t=120 s），而这时安全阀本来就该泄掉了。
     * 提到 7.40 给安全阀留出动作窗口，仍远低于 AZ-1K 的 7.50 / BSM 的 7.60。</p>
     */
    static final double AZ1_DRUM_PRESS_MPA = 7.40;
    /** AZ-2：功率 106% */
    static final double AZ2_POWER_MW = 5280.0;   // 110 %（2026-11-07 由 106 % 放宽，减少瞬态误动）
    /** AZ-3：功率 110% */
    static final double AZ3_POWER_MW = 5280.0;
    /** AZ-4：分离器水位高 / 低（%） */
    static final double AZ4_LEVEL_HI = 30.0, AZ4_LEVEL_LO = -40.0;
    /** AZ-6：主蒸汽母管低压（汽机失汽） */
    static final double AZ6_STEAM_MPA = 2.0;
    /** AZ-1K 快速保护的定值 */
    static final double AZ1K_PERIOD_S = 10.0;
    static final double AZ1K_REACTIVITY = 0.02;
    static final double AZ1K_POWER_MW = 5760.0;          // 120%
    static final double AZ1K_DRUM_PRESS_MPA = 7.50;
    static final double AZ1K_LEVEL_LO = -60.0;
    /** BSM 的定值 */
    static final double BSM_POWER_MW = 5760.0;           // 120%
    static final double BSM_DRUM_PRESS_MPA = 7.60;
    static final double BSM_LEVEL_LO = -70.0;
    static final double BSM_DELAY_S = 3.0;               // AZ-1K 动作后功率仍不回落多久 → BSM
    /** 各信号持续越限多久升级到 AZ-1K（s）；0 = 不升级 */
    static final double AZ1_ESCALATE_S = 8.0;
    static final double AZ2_ESCALATE_S = 6.0;
    static final double AZ3_ESCALATE_S = 3.0;
    static final double AZ4_ESCALATE_S = 10.0;
    /** 仿真步长（AutoControl.update 每 tick 一次） */
    static final double TICK_S = 0.05;

    AZPowerProtection az1Signal, az2Control, az3Control, az4Control, az5Control, az6Control;
    FASRControl fasrControl;
    FluidAutomaticRodController automaticRodController, lepController;
    //AutomaticRodController ar1Control, ar2Control, ar12Control, larControl;
    ArrayList<ControlRodChannel> ar1 = new ArrayList<>();
    ArrayList<ControlRodChannel> ar2 = new ArrayList<>();
    ArrayList<ControlRodChannel> lar = new ArrayList<>();
    private long simulationTime = 0; //simulation time in seconds
    private boolean timeUpdated = false;

    public AutoControl() {
        /* 热井水位控制接凝泵 1A/2A 的出口阀（2026-11-04）：1A 是定速泵、3×949 kg/s，
         * 原来无人调节，热井被一路抽到 −23 %。真实机组就是凝泵出口阀维持热井水位、
         * 增压泵出口阀常开、除氧器进水阀管除氧器水位。 */
        condenserWaterLevelControl.add(new OutflowWaterLevelControl(tg1.condenser, new WaterValve[] {condensate1A.get(0).dischargeValve, condensate1A.get(1).dischargeValve, condensate1A.get(2).dischargeValve}));
        condenserWaterLevelControl.add(new OutflowWaterLevelControl(tg2.condenser, new WaterValve[] {condensate2A.get(0).dischargeValve, condensate2A.get(1).dischargeValve, condensate2A.get(2).dischargeValve}));
        dearatorValves.forEach(valve -> {
            dearatorWaterControl.add(new InflowWaterLevelControl(valve.drain, new WaterValve[] {valve}));
            dearatorPressureControl.add(new InletSteamPressureControl(valve.drain, new SteamValve[] {((Dearator)valve.drain).steamInlet}));
        });
        short valveIterator[] = {0};
        pcs.dearatorMakeupValves.forEach(valve -> {
            dearatorMakeupControl.add(new InflowWaterLevelControl(valve.drain, new WaterValve[] {valve}));
            dearatorWaterAndMakeupControl.add(new InflowWaterLevelControl(valve.drain, new WaterValve[] {dearatorWaterControl.get(valveIterator[0]).valveArray[0], valve}));
            valveIterator[0]++;
        });
        /* 给水阀控制对象（2026-11-02 环路自检修）：
         * 主/辅给水阀 → 混合器 → **换热器管侧（二次侧）**，它们供的是二次侧的存水，
         * 原先却拿**汽包（一回路）**的水位当控制对象 —— 接线错位。
         * 后果是一个正反馈死锁：汽包水位高 → 关给水阀 → 二次侧无给水 → 不产汽
         * → 汽包蒸汽越积越多 → 汽包水位/压力继续涨 → 给水阀越关越死
         * （实测汽包压力一路升到 7.2 MPa，二次侧产汽从 2,100 kg/s 掉到 4 kg/s，
         *   汽轮机进汽 0、出力 0 MWe）。
         * 现在改成控制它们真正供给的对象：换热器管侧存水。 */
        /* 给水控制对象 = 汽包水位（单回路） */
        auxFeederControl.add(new InflowWaterLevelControl(
                mcc.drum1, new WaterValve[] {auxFeederValves.get(0)}));
        auxFeederControl.add(new InflowWaterLevelControl(
                mcc.drum2, new WaterValve[] {auxFeederValves.get(1)}));
        mainFeederControl.add(new InflowWaterLevelControl(
            mcc.drum1,
            new WaterValve[] {mainFeederValves.get(0), mainFeederValves.get(1), mainFeederValves.get(2)})
        );
        mainFeederControl.add(new InflowWaterLevelControl(
            mcc.drum2,
            new WaterValve[] {mainFeederValves.get(3), mainFeederValves.get(4), mainFeederValves.get(5)})
        );
        /* 给水阀全部改比例输出（2026-11-04）：开关式在这儿会死锁 —— 管侧水位 +4 %
         * 就全关，一关就没有进水、没有蒸发，水位几乎不动，阀永远等不到"水位偏低"。
         * 连续开度自带自衡：开度自动收到"刚好等于蒸发量"的位置。 */
        /* 给水阀用 PI：kp 0.05（1 % 偏差 → 5 % 开度）、ki 0.0004（1 % 偏差持续 1 s → 0.8 % 开度）。
         * 积分项负责"配平当前蒸发量"，比例项负责快速纠偏。 */
        mainFeederControl.forEach(c -> { c.enablePI(0.04, 1.5e-4); c.maxPosition = 0.40; });
        /* 三冲量：被控对象 = 换热器管侧水位，前馈 = 汽包出汽量 */
        feedwaterControl.add(new FeedwaterThreeElement(
                NPPSim.heatExchangers.get(0), mcc.drum1,
                new WaterValve[] {mainFeederValves.get(0), mainFeederValves.get(1), mainFeederValves.get(2)},
                new WaterValve[] {auxFeederValves.get(0)}));
        feedwaterControl.add(new FeedwaterThreeElement(
                NPPSim.heatExchangers.get(1), mcc.drum2,
                new WaterValve[] {mainFeederValves.get(3), mainFeederValves.get(4), mainFeederValves.get(5)},
                new WaterValve[] {auxFeederValves.get(1)}));
        auxFeederControl.forEach(c -> { c.enablePI(0.04, 1.5e-4); c.maxPosition = 0.40; });
        sdv_cControl.add(new OutletSteamPressureControl(mcc.drum1, new SteamValve[] {sdv_c.get(0), sdv_c.get(1), sdv_c.get(2), sdv_c.get(3), sdv_c.get(4), sdv_c.get(5), sdv_c.get(6), sdv_c.get(7)}));
        sdv_cControl.add(new OutletSteamPressureControl(mcc.drum2, new SteamValve[] {sdv_c.get(0), sdv_c.get(1), sdv_c.get(2),sdv_c.get(3), sdv_c.get(4), sdv_c.get(5), sdv_c.get(6), sdv_c.get(7)}));
        sdv_aControl.add(new OutletSteamPressureControl(mcc.drum1, new SteamValve[] {sdv_a.get(0)}));
        sdv_aControl.add(new OutletSteamPressureControl(mcc.drum2, new SteamValve[] {sdv_a.get(1)}));
        msv1Control.add(new OutletSteamPressureControl(mcc.drum1, new SteamValve[] {msvLoop1.get(0)}));
        msv1Control.add(new OutletSteamPressureControl(mcc.drum1, new SteamValve[] {msvLoop1.get(1), msvLoop1.get(2)}));
        msv1Control.add(new OutletSteamPressureControl(mcc.drum1, new SteamValve[] {msvLoop1.get(3), msvLoop1.get(4), msvLoop1.get(5)}));
        msv2Control.add(new OutletSteamPressureControl(mcc.drum2, new SteamValve[] {msvLoop2.get(0)}));
        msv2Control.add(new OutletSteamPressureControl(mcc.drum2, new SteamValve[] {msvLoop2.get(1), msvLoop2.get(2)}));
        msv2Control.add(new OutletSteamPressureControl(mcc.drum2, new SteamValve[] {msvLoop2.get(3), msvLoop2.get(4), msvLoop2.get(5)}));
        /* 主汽门自动控制（2026-11-03 修正控制对象）。
         *
         * 旧接法把**一回路汽包**（6.9 MPa）当控制对象，设定值也写 6.90 ——
         * 可 TG 主汽门在**二回路主蒸汽母管**（4.0 MPa）上，两边不是一回事：
         *   · 汽包 5.6~6.2 MPa 永远低于 6.9 → 控制器判"压力不够" → 关阀；
         *   · 每台机还被 drum1、drum2 两个控制器同时控同一对阀，互相打架。
         * 结果主汽门被越关越小，汽轮机只拿到 ~90 kg/s，出力 60~100 MWe（应为 700+）。
         * 现在：对象改成主蒸汽母管 steamPiping，设定值 4.0 MPa（= Separator 设计压力），
         * 每台机只保留一个控制器。 */
        /* 保持 4 个控制槽（TGUI 会读 get(0) / get(2)），但每台机只**驱动一个**：
         * 索引 0/1 属 TG-1，2/3 属 TG-2，update() 里只跑 0 和 2。
         * 全部改控主蒸汽母管（4.0 MPa），不再看一回路汽包。 */
        Connectable steamSource = (NPPSim.steamPiping != null) ? NPPSim.steamPiping : mcc.drum1;
        tgValveControl.add(new OutletSteamPressureControl(
                steamSource, new SteamValve[] {TG1InletValves.get(0), TG1InletValves.get(1)}));
        tgValveControl.add(new OutletSteamPressureControl(
                steamSource, new SteamValve[] {TG1InletValves.get(0), TG1InletValves.get(1)}));
        tgValveControl.add(new OutletSteamPressureControl(
                steamSource, new SteamValve[] {TG2InletValves.get(0), TG2InletValves.get(1)}));
        tgValveControl.add(new OutletSteamPressureControl(
                steamSource, new SteamValve[] {TG2InletValves.get(0), TG2InletValves.get(1)}));
        tgValveControl.forEach(controller -> {
            /* 单回路：母管 = 汽包压力 6.90，留 0.3 MPa 节流 → 6.60 */
            controller.setpoint = 6.60;
        });
        dearatorPressureControl.forEach(controller -> {
            controller.setpoint = 1.0;
        });
        sdv_cControl.forEach(controller -> {
            controller.setEnabled(true);
            controller.setpoint = 6.98; 
            controller.activationTreshold = 6.98;
        });
        sdv_aControl.forEach(controller -> {
        controller.setEnabled(true);
        controller.setpoint = 7.06; 
        controller.activationTreshold = 7.06;
        });
        //activate msv controllers and set setpoints for group 1(0), 2(1-2), 3(3-5)
        msv1Control.forEach(controller -> {
        controller.setEnabled(true);
        });
        msv1Control.get(0).setpoint = 7.36; 
        msv1Control.get(0).activationTreshold = 7.36; 
        msv1Control.get(1).setpoint = 7.45; 
        msv1Control.get(1).activationTreshold = 7.45; 
        msv1Control.get(2).setpoint = 7.55; 
        msv1Control.get(2).activationTreshold = 7.55; 
        
        msv2Control.forEach(controller -> {
        controller.setEnabled(true);
        });
        msv2Control.get(0).setpoint = 7.36;
        msv2Control.get(0).activationTreshold = 7.36; 
        msv2Control.get(1).setpoint = 7.45;
        msv2Control.get(1).activationTreshold = 7.45; 
        msv2Control.get(2).setpoint = 7.55;
        msv2Control.get(2).activationTreshold = 7.55; 
        
        az1Control = new AZ1Control();
        /* AZ-1~AZ-6 六个信号：除 AZ-5（手动停堆，真插棒）外**都不动棒**，
         * 只报警 + 联锁，持续越限才升级到 AZ-1K / BSM（见类头注释的表格）。
         * altAlarm=true 的是 AZ-2/AZ-3（共用备用警声 ALARM_3），便于听声区分。 */
        az1Signal  = new AZPowerProtection("AZ-1", false, false);
        az2Control = new AZPowerProtection("AZ-2", true,  false);
        az3Control = new AZPowerProtection("AZ-3", true,  false);
        az4Control = new AZPowerProtection("AZ-4", false, false);
        az5Control = new AZPowerProtection("AZ-5", false, true);    // 手动紧急停堆：全棒
        az6Control = new AZPowerProtection("AZ-6", false, false);
        fasrControl = new FASRControl();

        core.coreArray.forEach(row -> {
            row.forEach(channel -> {
                if (channel instanceof ACRChannel) {
                    ar1.add((ControlRodChannel)channel);
                } else if (channel instanceof SACRChannel) {
                    ar2.add((ControlRodChannel)channel);
                } else if (channel instanceof LACChannel) {
                    lar.add((ControlRodChannel)channel);
                }
            });
        });
        automaticRodController = new FluidAutomaticRodController();
    }

    public void update() {
        sdv_c.forEach(valve -> {
            valve.setLocked(valve.drain.getPressure() > 0.023 || valve.drain.getSteamTemperature() > 100.0);
        });
        if(sdv_cControl.get(0).isEnabled()) {
            Runnable controller = mcc.drum1.getPressure() > mcc.drum2.getPressure() ? sdv_cControl.get(0)::update: sdv_cControl.get(1)::update;
            controller.run();
        }
        /* 主汽门自动：每台机只跑一个控制器（2026-11-03）。
         * 原来是"汽包1 高就用控制器 0、否则用控制器 1"，两个控制器控的却是**同一对阀**，
         * 一旦两边同时动作就互相打架。现在固定用 0（TG-1）和 2（TG-2）。 */
        if(tgValveControl.size() > 0 && tgValveControl.get(0).isEnabled()) {
            tgValveControl.get(0).update();
        }
        if(tgValveControl.size() > 2 && tgValveControl.get(2).isEnabled()) {
            tgValveControl.get(2).update();
        }
        condenserWaterLevelControl.forEach(controller -> {
            if(controller.isEnabled()) {
                controller.update();
            }
        });
        for (short i = 0; i < 4; i++) {
            if (dearatorWaterAndMakeupControl.get(i).isEnabled()) {
                dearatorWaterAndMakeupControl.get(i).update();
            } else if (dearatorWaterControl.get(i).isEnabled()) {
                dearatorWaterControl.get(i).update();
            } else if (dearatorMakeupControl.get(i).isEnabled()) {
                dearatorMakeupControl.get(i).update();
            }
        }
        dearatorPressureControl.forEach(controller -> {
            if (controller.isEnabled()) {
                controller.update();
            }
        });
        if (feedwaterAuto && !feedwaterControl.isEmpty()) {
            for (FeedwaterThreeElement controller : feedwaterControl) {
                controller.setEnabled(true);
                controller.update(TICK_S);
            }
        } else {
            auxFeederControl.forEach(controller -> {
                if (controller.isEnabled()) {
                    controller.update();
                }
            });
            mainFeederControl.forEach(controller -> {
                if (controller.isEnabled()) {
                    controller.update();
                }
            });
        }
        sdv_aControl.forEach(controller -> {
            if(controller.isEnabled()) {
                controller.update();
            }
        });
        msv1Control.forEach(controller -> {
            if(controller.isEnabled()) {
                controller.update();
            }
        });
        msv2Control.forEach(controller -> {
            if(controller.isEnabled()) {
                controller.update();
            }
        });
        if (azAutoEnabled) {
            /* AZ 分层（2026-11-03 重写）：
             * AZ-1~AZ-6 只判断 + 报警 + 联锁；AZ-1K 是被升级的"最后一手"，
             * BSM 再兜底。AZ-5 是手动按钮，没有自动定值。 */
            az1Signal.update();
            az2Control.update();
            az3Control.update();
            az4Control.update();
            az6Control.update();
            if (az1Control.isEnabled()) {
                az1Control.update();
            }
            fasrControl.update();          // BSM：AZ-1K 压不住才动作
        } else if (fasrControl.isTripped()) {
            // 关闭自动 AZ 时 BSM 不自动触发；已跳闸的等复位
            fasrControl.update();
        }
        /* 顺序闭锁必须跟着**真实棒位**刷新，不能只在「自动 AZ 打开」时才更新
         * （2026-11-07）：装载标准工况会关掉自动 AZ，若不刷新，闭锁会一直保持
         * 冷启动时的 true，АР 提不了棒 → 功率单向下滑 → 汽机逆功率 → AZ-1 停堆。 */
        fasrControl.refreshSequenceLock();
        automaticRodController.update();
    }

    /**
     * 面板"复位 AZ"：复位**全部**保护（AZ-1K / BAZ / AZ-2 / AZ-3 / AZ-4 / AZ-5）。
     * 之前这里只复位了 AZ-1K 与 BAZ，AZ-2~AZ-5 的 tripped 永远不清 → 它们的警声也永远不会停，
     * 表现为"AZ 声音关断有延迟/关不掉"。另外顺手把残留的音频请求清干净，确保按下即静音。
     */
    public void resetAllAz() {
        az1Control.reset();
        az1Signal.reset();
        fasrControl.reset();
        az2Control.reset();
        az3Control.reset();
        az4Control.reset();
        az5Control.reset();
        az6Control.reset();
        // 兜底：任何一方因异常残留的跳闸声请求都释放掉（正常路径下 reset() 已逐个释放）
        soundProvider.releaseAll(az1Control);
        soundProvider.releaseAll(az1Signal);
        soundProvider.releaseAll(fasrControl);
        soundProvider.releaseAll(az2Control);
        soundProvider.releaseAll(az3Control);
        soundProvider.releaseAll(az4Control);
        soundProvider.releaseAll(az6Control);
    }

    class InflowWaterLevelControl implements Serializable {
        WaterValve[] valveArray;
        Connectable target;
        private boolean enabled = false;
        protected double previousLevel, currentLevel, deltaLevel, deltaLevelSetpoint, setpoint = 0.0;
        /** 比例输出模式（2026-11-04）：阀门开度连续给定，而不是"全开/全关" */
        private boolean proportional = false;
        /** 比例增益：水位偏差 1 % → 开度变化 kp */
        private double proportionalKp = 0.15;
        /** 积分项（PI 的 I）：稳态开度就是它 —— 纯比例在积分型对象上会留下永久偏差 */
        private double integral = 0.0;
        /** 积分增益：每 tick 按偏差累加 */
        private double proportionalKi = 0.0;
        /**
         * 最大开度（2026-11-07）：给水阀不许一次全开。
         *
         * <p>水位一低就开到 100 % 会瞬间灌进 3 000+ kg/s（额定只要约 1 000），
         * 汽包水温被冷水拉低 → 压力掉 → 主汽门关 → 汽机逆功率。
         * 限到 60 % 留足调节余量，也给汽包水温一个缓冲。</p>
         */
        double maxPosition = 1.0;

        /**
         * 切成比例输出。开关式控制遇到"纯积分型"对象（换热器管侧存水 = 进水 − 蒸发）
         * 会死锁：水位偏高 → 全关 → 没有进水就没有蒸发 → 水位永远降不下来 → 阀门永远关着。
         * 连续开度自带自衡：水位高就关小一点、低就开大一点，总能找到"等于蒸发量"的开度。
         */
        void enableProportional(double kp) {
            this.proportional = true;
            this.proportionalKp = kp;
            this.proportionalKi = 0.0;
        }

        /**
         * 切成 PI 输出（2026-11-04）。
         *
         * <p>给水阀控的是换热器管侧存水，而存水是"进水 − 蒸发"的纯积分量。
         * 纯比例控制在这里做不到任意流量：稳态时开度完全由水位偏差决定，
         * 想多送水就必须让水位长期偏离设定值（实测阀停在 0.13、水位 +1.5 %，
         * 给水只有 860 kg/s，二次侧产汽上不去，汽包压力一路掉到跳机）。</p>
         *
         * <p>PI 的积分项就是"维持当前流量所需的开度"：水位回到设定值时，
         * 积分项正好等于配平蒸发量所需开度，不留静差。</p>
         */
        void enablePI(double kp, double ki) {
            this.proportional = true;
            this.proportionalKp = kp;
            this.proportionalKi = ki;
        }
    
        public InflowWaterLevelControl(Connectable target, WaterValve[] valveArray) {
            this.valveArray = valveArray;
            this.target = target;
        }
        
        public void setWaterValveStateByLevel(double level, double setPoint) {
            
        }
        
        public void setWaterValveStateByLevel(double level) {
            setWaterValveStateByLevel(level, 0.0);
        }
    
        /**
         * 水位控制（2026-11-02 环路自检加比例项）。
         *
         * <p>原实现只有**微分项**：水位在动就开/关，水位停住就不动。
         * 这意味着"水位停在偏差上"的工况下阀门永远不动作 → 给水回路死锁
         * （除氧器不进水、给水泵无源、二次侧无汽、汽机出力 0）。</p>
         *
         * <p>现在加比例项：水位低于设定值 1% → 开阀；高于 1% → 关阀；
         * 落在带内时仍用原来的微分项做微调（避免在目标附近来回大幅摆动）。</p>
         */
        public void update() {
            currentLevel = target.getWaterLevel();
            double errP = setpoint - currentLevel;     // 正 = 水位偏低 → 该开大
            if (proportional) {
                if (proportionalKi > 0.0) {
                    integral += errP * proportionalKi;
                    integral = Math.max(0.0, Math.min(1.0, integral));
                }
                double pos = integral + 0.5 + errP * proportionalKp;
                pos = Math.max(0.0, Math.min(maxPosition, pos));
                for (WaterValve valve : valveArray) {
                    valve.setAutoState(1);
                    valve.setState(1);
                    valve.setPosition((float) pos);
                }
                previousLevel = currentLevel;
                return;
            }
            deltaLevel = currentLevel - previousLevel;
            deltaLevelSetpoint = (setpoint - currentLevel) / 200;
            double err = setpoint - currentLevel;      // 正 = 水位偏低
            if (err > 1.0) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else if (err < -1.0) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(0);
                }
            } else if (deltaLevel > deltaLevelSetpoint + 0.001) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(0);
                }
            } else if (deltaLevel < deltaLevelSetpoint - 0.001) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
            previousLevel = currentLevel;
        }
    
        public void setSetpoint(double setpoint) {
            this.setpoint = setpoint;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!enabled) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
        }
    }
    
    class OutflowWaterLevelControl extends InflowWaterLevelControl implements Serializable {
        
        public OutflowWaterLevelControl(Connectable target, WaterValve[] valveArray) {
            super(target, valveArray);
        }
        
        /**
         * 流出侧水位控制（2026-11-02 加比例项）。
         *
         * <p>这个回路控的是**凝结水增压泵（1B/2B）的出口阀**——即凝汽器热井的出口通道。
         * 原实现和流入侧一样只有微分项，而它比流入侧更致命：</p>
         * <ul>
         *   <li>整定完的瞬间热井水位稳定（微分 0），阀门停在 0（全关）；</li>
         *   <li>没有任何扰动让它"动起来"，阀门就永远停在 0 —— 凝结水一滴也送不出去；</li>
         *   <li>于是凝结水联箱只有 0.12 MPa（泵有压头但阀全关，母管算出的压力≈静压），
         *       低压加热器出口压力 0.13 MPa &lt; 除氧器 0.53 MPa，除氧器进水阀压差为负，
         *       **整条二回路从第一级就断了**（实测标准工况加载后 4 分钟除氧器零进水）。</li>
         * </ul>
         *
         * <p>现在加比例项：水位高于设定值 1% 就开阀排水，低于 1% 就关阀，带内仍用微分微调。</p>
         */
        @Override
        public void update() {
            currentLevel = target.getWaterLevel();
            deltaLevel = currentLevel - previousLevel;
            deltaLevelSetpoint = (setpoint - currentLevel) / 200;
            double err = setpoint - currentLevel;      // 正 = 水位偏低（该关阀）
            if (err > 1.0) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(0);
                }
            } else if (err < -1.0) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else if (deltaLevel > deltaLevelSetpoint + 0.001) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else if (deltaLevel < deltaLevelSetpoint - 0.001) {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(0);
                }
            } else {
                for (WaterValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
            previousLevel = currentLevel;
        }
        
    }
     
    class InletSteamPressureControl implements Serializable {
        SteamValve[] valveArray;
        Connectable target;
        private boolean enabled = false;
        protected double previousPressure, currentPressure, deltaPressure, deltaPressureSetpoint, setpoint = 0.0;

        public InletSteamPressureControl(Connectable target, SteamValve[] valveArray) {
            this.valveArray = valveArray;
            this.target = target;
        }

        public void update() {
            currentPressure = target.getPressure();
            deltaPressure = currentPressure - previousPressure;
            deltaPressureSetpoint = (setpoint - currentPressure) / 200;
            if (deltaPressure > deltaPressureSetpoint + 0.0001) {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(0);
                }
            } else if (deltaPressure < deltaPressureSetpoint - 0.0001) {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
            previousPressure = currentPressure;
        }

        public void setSetpoint(double setpoint) {
            this.setpoint = setpoint;
        }
        
        public double getSetpoint() {
            return setpoint;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!enabled) {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
        }
    }

    class OutletSteamPressureControl extends InletSteamPressureControl {
        private double activationTreshold = 0.0;

        public OutletSteamPressureControl(Connectable target, SteamValve[] valveArray) {
            super(target, valveArray);
        }

        /** 压力控制死区 MPa（约 1.2 % 额定） */
        static final double PRESSURE_DEADBAND = 0.05;

        /**
         * 最小开度（2026-11-07）。压力低时不许把主汽门关死：
         * 关到 0 立刻逆功率（出力 −17 MW），15 s 后跳机 → AZ-1 停堆。
         * 真实机组的调节阀也带最小开度限位，不会全关。
         */
        double minPosition = 0.15;

        /**
         * 2026-11-04 改为"比例 + 死区"。
         *
         * <p>旧实现只比**压力变化率**：deltaPressure 与 (setpoint − p)/200 相比。
         * 偏差一大，它就要求一个不可能达到的升压速率：母管 4.0 → 3.0 时要求每 tick
         * 涨 0.005 MPa，而实测 delta 是负的 → 判"该关阀" → 关阀使压力更低 → 关得更狠。
         * 主汽门就是这样被关到 0 并卡死的（探针实测 t=25 s 进汽 55 → 0 kg/s），
         * 汽机逆功率跳机、凝汽器没有凝结水，整条凝结水回收链跟着断。</p>
         *
         * <p>现在母管高于设定 + 死区 → 开阀多抽汽，低于设定 − 死区 → 关阀，带内保持。</p>
         */
        @Override
        public void update() {
            currentPressure = target.getPressure();
            double err = currentPressure - setpoint;     // 正 = 母管压力偏高 → 该多抽汽
            if (err > PRESSURE_DEADBAND
                    && (currentPressure > activationTreshold || valveArray[0].getPosition() != 0.0)) {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(2);
                }
            } else if (err < -PRESSURE_DEADBAND) {
                for (SteamValve valve: valveArray) {
                    if (valve.getPosition() > minPosition) {
                        valve.setAutoState(0);
                    } else {
                        valve.setAutoState(1);
                        valve.setPosition((float) minPosition);
                    }
                }
            } else {
                for (SteamValve valve: valveArray) {
                    valve.setAutoState(1);
                }
            }
            previousPressure = currentPressure;
        }

        public void setActivationTreshold(double treshold) {
            this.activationTreshold = treshold;
        }
    }
    
    /**
     * 是否为"玩家手动操控的棒"（Manual Control Rod / Manual Control Rod Modernized）。
     *
     * <p>用**精确类匹配**：ACR/LAC/LEP 分别 extends MCR/MCRM，但它们是自动棒，
     * 不该被当成玩家棒；只有 getClass() 正好是 MCRChannel / MCRMChannel 才是玩家手动棒。</p>
     */
    static boolean isManualRod(ControlRodChannel channel) {
        return channel.getClass() == MCRChannel.class || channel.getClass() == MCRMChannel.class;
    }

    /**
     * 全堆 SCRAM（插入控制棒）。
     *
     * @param scram      true = 插入，false = 复位/抽出
     * @param fullScram  true = 紧急停堆，插**全部**棒（含玩家手动棒）；
     *                    false = 自动 AZ 保护，只插自动棒/FASR，跳过玩家手动棒
     *                    （否则玩家选中的手动棒被锁死，无法再操控/干预）。
     */
    void scramAllRods(boolean scram, boolean fullScram) {
        core.coreArray.forEach(row -> {
            row.forEach(channel -> {
                if (channel instanceof ControlRodChannel) {
                    ControlRodChannel rod = (ControlRodChannel) channel;
                    if (!fullScram && scram && isManualRod(rod)) {
                        return;
                    }
                    rod.setAutoState(scram ? 2 : 1);
                    rod.setState(scram ? 2 : 1);
                    rod.setScram(scram);
                }
            });
        });
    }

    /**
     * AZ-1K —— 快速功率保护（2026-11-03 重写）。
     *
     * <p>这是**真正会动棒**的两套保护之一（另一套是 BSM）。盯的是"功率涨得太快"这一类
     * 瞬变，而不是绝对功率值：</p>
     * <ul>
     *   <li>中子周期 0 &lt; T &lt; 10 s（功率倍增过快）；</li>
     *   <li>反应性 &gt; +0.02 连续 1 s（正的引入且不收敛）；</li>
     *   <li>热功率 &gt; 120%（5760 MW）；</li>
     *   <li>一回路压力 &gt; 7.50 MPa；</li>
     *   <li>分离器水位 &lt; −60 %。</li>
     * </ul>
     * <p>动作：全棒快速插入（含玩家手动棒）+ ALARM_2。AZ-1~AZ-4 持续越限也会升级到这里，
     * 但升级是"事后再兜底"，不是一越限就插棒。</p>
     */
    class AZ1Control implements Serializable {
        private boolean tripped = false;
        private boolean enabled = true;
        /** 反应性越限累计 tick（1 s = 20 tick） */
        private int highReactivityTicks = 0;
        /** 功率越限累计 tick（2026-11-07：2 s 延时，躲开压力瞬变） */
        private transient int highPowerTicks = 0;

        public void update() {
            // 「自动 AZ」关闭时不做任何自动跳闸判断；手动 trip() 不受影响。
            if (tripped || !azAutoEnabled) {
                return;
            }
            /* 周期保护只在堆功率 > 5% 额定时投入（真实机组的功率通道投入条件），
             * 否则冷启动升功率那一段会把它当成"周期太短"误跳。 */
            double period = core.getPeriod();
            boolean armed = core.getThermalPower() > RATED_POWER_MW * 0.05;
            if (armed && period > 0 && period < AZ1K_PERIOD_S) {
                trip("Period < 10 s");
                return;
            }
            if (core.getReactivity() > AZ1K_REACTIVITY) {
                highReactivityTicks++;
                if (highReactivityTicks * TICK_S >= 1.0) {
                    highReactivityTicks = 0;
                    trip("High Neutron Rate");
                    return;
                }
            } else {
                highReactivityTicks = 0;
            }
            /* 功率判据加 2 s 延时（2026-11-07）：单回路下汽包压力回升时空泡塌陷，
             * 功率会在 2~3 s 内从 4 950 冲到 6 000 以上再被 АР 压回。真实机组的
             * 功率高保护带 2~3 s 延时正是为了躲开这种瞬态，原来立刻跳会被误动作打掉。 */
            if (core.getThermalPower() > AZ1K_POWER_MW) {
                if (++highPowerTicks * TICK_S >= 2.0) {
                    highPowerTicks = 0;
                    trip("High Thermal Power > 120%");
                    return;
                }
            } else {
                highPowerTicks = 0;
            }
            if (mcc.drum1.getPressure() > AZ1K_DRUM_PRESS_MPA
                    || mcc.drum2.getPressure() > AZ1K_DRUM_PRESS_MPA) {
                trip("Drum Pressure > 7.50 MPa");
                return;
            }
            if (mcc.drum1.getWaterLevel() < AZ1K_LEVEL_LO
                    || mcc.drum2.getWaterLevel() < AZ1K_LEVEL_LO) {
                trip("Water Level < -60 %");
            }
        }

        public void trip(String reason) {
            if (tripped) {
                return;
            }
            soundProvider.request(soundProvider.ALARM_2, this);   // 以本保护为 owner：复位即时、不误停他人
            recordEvent(Localization.t("AZ-1 Trip Signal: ") + reason);
            tripped = true;
            // 快速保护：插**全部**棒，含玩家手动棒
            scramAllRods(true, true);
        }

        public void reset() {
            if (!tripped) {
                return;
            }
            recordEvent(Localization.t("AZ-1 Reset"));
            tripped = false;
            highReactivityTicks = 0;
            soundProvider.release(soundProvider.ALARM_2, this);   // 立即静音（旧版有 500ms 防抖 → 听感延迟）
            scramAllRods(false, true);
        }

        public boolean isTripped() {
            return tripped;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    /**
     * AZ-1 ~ AZ-6 六个独立保护信号（2026-11-03 重写为真实分层逻辑）。
     *
     * <p><b>除 AZ-5（手动紧急停堆）外，这些保护都不动棒</b>：只做"灯 + 记录 + 联锁"，
     * 持续越限一段时间仍不回落，才升级到 AZ-1K（或由 BSM 兜底）。这样就不会出现
     * "功率刚到 108% 全堆棒一头砸下去"的过度反应。</p>
     *
     * <p>每个信号盯自己的被测量（按 name 分派，存档里 name 是 final 字段，老存档也能正确识别）：</p>
     * <pre>
     *   AZ-1 一回路压力 &gt; 7.26 MPa        → 灯 + 记录，8 s 升级 AZ-1K
     *   AZ-2 热功率 &gt; 106%                → 灯 + АР 设定降到 98%，6 s 升级
     *   AZ-3 热功率 &gt; 110%                → 灯 + АР 设定降到 90% + 主汽门关小，3 s 升级
     *   AZ-4 水位 &gt; +30% / &lt; −40%         → 灯 + 给水联锁，10 s 升级
     *   AZ-5 手动                           → 全棒插入
     *   AZ-6 主蒸汽母管 &lt; 2.0 MPa（堆在运行）→ 灯 + 两台汽机脱扣，不动棒
     * </pre>
     */
    class AZPowerProtection implements Serializable {
        private boolean tripped = false;
        private final String name;
        /** 备用警声（ALARM_3）：AZ-2 / AZ-3 用，便于听声区分 */
        private final boolean altAlarm;
        /**
         * 动作时是否真的插棒。只有 AZ-5（手动停堆）为 true；
         * AZ-1~AZ-4、AZ-6 一律 false —— 这就是"不轻易动 AZ 棒"的落点。
         */
        private final boolean fullScram;
        /** 越限累计 tick（用于升级判断） */
        private transient int overTicks = 0;

        AZPowerProtection(String name, boolean altAlarm, boolean fullScram) {
            this.name = name;
            this.altAlarm = altAlarm;
            this.fullScram = fullScram;
        }

        /** 本保护对应的警声 clip（跳闸/复位共用，保证停的是同一条） */
        private javax.sound.sampled.Clip alarmClip() {
            return altAlarm ? soundProvider.ALARM_3 : soundProvider.ALARM_2;
        }

        /** 持续越限多久升级到 AZ-1K（s）；0 = 不升级（AZ-5/AZ-6） */
        private double escalateSeconds() {
            if ("AZ-1".equals(name)) return AZ1_ESCALATE_S;
            if ("AZ-2".equals(name)) return AZ2_ESCALATE_S;
            if ("AZ-3".equals(name)) return AZ3_ESCALATE_S;
            if ("AZ-4".equals(name)) return AZ4_ESCALATE_S;
            return 0;
        }

        /** 当前的被测量是否越限 */
        private boolean exceeded() {
            switch (name) {
                case "AZ-1":
                    return mcc.drum1.getPressure() > AZ1_DRUM_PRESS_MPA
                            || mcc.drum2.getPressure() > AZ1_DRUM_PRESS_MPA;
                case "AZ-2":
                    return core.getThermalPower() > AZ2_POWER_MW;
                case "AZ-3":
                    return core.getThermalPower() > AZ3_POWER_MW;
                case "AZ-4": {
                    double l1 = mcc.drum1.getWaterLevel(), l2 = mcc.drum2.getWaterLevel();
                    return l1 > AZ4_LEVEL_HI || l2 > AZ4_LEVEL_HI
                            || l1 < AZ4_LEVEL_LO || l2 < AZ4_LEVEL_LO;
                }
                case "AZ-6":
                    /* 主蒸汽母管低压（失汽）：只有在"反应堆在运行 + 汽机已经挂网带负荷"时才算数。
                     * 冷启动/盘车阶段母管本来就没压，不能因此跳机。 */
                    return core.getThermalPower() > RATED_POWER_MW * 0.05
                            && (tg1.isSynced() || tg2.isSynced())
                            && NPPSim.steamPiping != null
                            && NPPSim.steamPiping.getPressure() < AZ6_STEAM_MPA;
                default:
                    return false;      // AZ-5 无自动定值
            }
        }

        public void update() {
            // 自动保护受总开关控制：关闭「自动 AZ」后即使越限也不动作，
            // 仍可通过面板上的手动 AZ 按钮触发信号。
            if (!azAutoEnabled) {
                return;
            }
            if (tripped) {
                // 已经发过信号：继续盯着工况——持续越限才升级到 AZ-1K
                if (exceeded()) {
                    countEscalation();
                } else {
                    overTicks = 0;
                }
                return;
            }
            if (!exceeded()) {
                overTicks = 0;
                return;
            }
            // 越限的第一个 tick 就发信号（报警 + 联锁），再谈升级
            trip();
            countEscalation();
        }

        /** 持续越限计时；够久就升级到 AZ-1K（真正插棒的那一步） */
        private void countEscalation() {
            double escalate = escalateSeconds();
            if (escalate <= 0 || az1Control.isTripped()) {
                return;
            }
            overTicks++;
            if (overTicks * TICK_S >= escalate) {
                overTicks = 0;
                recordEvent(name + " 持续越限 " + (int) escalate + " s → 升级 AZ-1K");
                az1Control.trip(name + " sustained");
            }
        }

        public void trip() {
            if (tripped) {
                return;
            }
            tripped = true;
            soundProvider.request(alarmClip(), this);
            recordEvent(Localization.t(name + " Trip Signal"));
            applyInterlock();
            if (fullScram) {
                scramAllRods(true, true);      // 只有 AZ-5 会走到这里
            }
        }

        /** 联锁动作：全部是"调设定/阀门/切机"，一根棒都不插 */
        private void applyInterlock() {
            switch (name) {
                case "AZ-1":
                    // 压力高：泄压阀本来就按压力自动开（SDV-c 6.98 MPa 起），这里只记录
                    break;
                case "AZ-2":
                    // 功率 106%：把 АР 设定压到 98% 额定，温和降功率
                    autoControlSetpointRatio(0.98);
                    break;
                case "AZ-3":
                    // 功率 110%：АР 设定压到 90%，并把两台机主汽门关小到 70%
                    autoControlSetpointRatio(0.90);
                    closeTurbineInletValves();
                    break;
                case "AZ-4":
                    // 水位越限：高水位关主给水阀，低水位开辅助给水阀
                    feedwaterInterlock();
                    break;
                case "AZ-6":
                    // 主蒸汽低压（失汽）：两台汽机脱扣，不动棒
                    if (!tg1.isTripped()) {
                        tg1.trip("AZ-6 Low Main Steam Pressure");
                    }
                    if (!tg2.isTripped()) {
                        tg2.trip("AZ-6 Low Main Steam Pressure");
                    }
                    break;
                default:
                    break;
            }
        }

        /** 只降不升：把 АР 设定压到 max(当前值, 目标值) 以下（不能因为越限反而把设定抬高） */
        private void autoControlSetpointRatio(double ratio) {
            if (automaticRodController == null) {
                return;
            }
            double target = RATED_POWER_MW * ratio;
            if (target < automaticRodController.getSetpoint()) {
                automaticRodController.setSetpoint(target);
            }
        }

        private void closeTurbineInletValves() {
            TG1InletValves.forEach(v -> v.setAutoState(0));
            TG2InletValves.forEach(v -> v.setAutoState(0));
        }

        private void feedwaterInterlock() {
            boolean high = mcc.drum1.getWaterLevel() > AZ4_LEVEL_HI
                    || mcc.drum2.getWaterLevel() > AZ4_LEVEL_HI;
            if (high) {
                mainFeederValves.forEach(v -> v.setAutoState(0));     // 关主给水
            } else {
                auxFeederValves.forEach(v -> v.setAutoState(2));      // 开辅助给水
            }
        }

        public void reset() {
            if (!tripped) {
                return;
            }
            recordEvent(Localization.t(name + " Reset"));
            tripped = false;
            overTicks = 0;
            soundProvider.release(alarmClip(), this);   // 立即静音；若共用同一条警声，另一台仍跳闸时继续响
            if (fullScram) {
                scramAllRods(false, true);              // 只有 AZ-5 插过棒，才需要抽回
            }
        }

        public boolean isTripped() {
            return tripped;
        }
    }

    class FASRControl implements Serializable {
        private boolean tripped = false, sequenceLock = false;
        private ArrayList<FASRChannel> channels = new ArrayList<>();
        /** BSM 自动判据：AZ-1K 后功率仍高的持续 tick 计数 */
        private transient int bsmHoldTicks = 0;
        
        public FASRControl() {
            core.coreArray.forEach(row -> {
                row.forEach(channel -> {
                    if (channel instanceof FASRChannel) {
                        channels.add((FASRChannel)channel);
                    }
                });
            });
        }
        
        /**
         * 顺序闭锁：**只要还有一根 AZ-5 棒没有拔到顶（position &gt; 0），
         * 除 AZ-5 组以外的控制棒一律不许拔出**（2026-11-02 按用户要求恢复）。
         *
         * <p>棒位口径：1.0 = 全插、0.0 = 全提。冷态全部 211 根都在 1.0，
         * 所以启动时先选 AZ-5 组（红色棒）逐组拔到顶，其他棒才解锁；
         * AZ-5 组自身在闭锁期间仍可操作，否则会死锁。</p>
         *
         * <p>0.005 的容差：AZ 棒以每 tick 0.002857 的步长走到 0 时会精确停在 0，
         * 但存档/浮点累加可能留下 1e-3 量级的残值，容差避免"永远差一点点"。</p>
         */
        public void update() {
            updateSequenceLock();
            // 自动 BSM：只有「自动 AZ」打开时才自动动作（手动按钮不受影响）
            if (azAutoEnabled && !tripped) {
                evaluateAutomatic();
            }
        }

        /** 顺序闭锁：还有 AZ-5 棒没拔到顶就拦住其它棒 */
        private void updateSequenceLock() {
            for (FASRChannel channel: channels) {
                if (channel.getPosition() > 0.005f) {
                    sequenceLock = true;
                    return;
                }
            }
            sequenceLock = false;
        }

        /**
         * BSM（快速事故保护）自动判据（2026-11-03 重写）。
         *
         * <p>它**不是**普通功率保护，而是"AZ-1K 都压不住"时的最后一手：</p>
         * <ul>
         *   <li>AZ-1K 已动作，功率仍 &gt; 120% 超过 3 s；</li>
         *   <li>一回路压力 &gt; 7.60 MPa（接近安全阀）；</li>
         *   <li>分离器水位 &lt; −70 %（严重失水）。</li>
         * </ul>
         * <p>动作：全部控制棒快速插入，并让 BSM 棒以最快速度下落。</p>
         */
        private void evaluateAutomatic() {
            if (az1Control.isTripped() && core.getThermalPower() > BSM_POWER_MW) {
                bsmHoldTicks++;
                if (bsmHoldTicks * TICK_S >= BSM_DELAY_S) {
                    bsmHoldTicks = 0;
                    trip("AZ-1K 未抑制，功率仍 > 120%");
                    return;
                }
            } else {
                bsmHoldTicks = 0;
            }
            if (mcc.drum1.getPressure() > BSM_DRUM_PRESS_MPA
                    || mcc.drum2.getPressure() > BSM_DRUM_PRESS_MPA) {
                trip("Drum Pressure > 7.60 MPa");
                return;
            }
            if (mcc.drum1.getWaterLevel() < BSM_LEVEL_LO
                    || mcc.drum2.getWaterLevel() < BSM_LEVEL_LO) {
                trip("Water Level < -70 %");
            }
        }
        
        public void trip(String reason) {
            if (tripped) {
                return;
            }
            soundProvider.request(soundProvider.ALARM_2, this);
            recordEvent(Localization.t("BAZ Trip Signal: ") + reason);
            tripped = true;
            core.coreArray.forEach(row -> {
                row.forEach(channel -> {
                    if (channel instanceof ControlRodChannel) {
                        ((ControlRodChannel) channel).setAutoState(2);
                        ((ControlRodChannel) channel).setState(2);
                        ((ControlRodChannel) channel).setScram(tripped);
                    }
                });
            });
            channels.forEach(channel -> {
                channel.setFastScram(true);
            });
        }   
        
        public void reset() {
            recordEvent(Localization.t("BAZ Reset"));
            soundProvider.release(soundProvider.ALARM_2, this);
            tripped = false;
            core.coreArray.forEach(row -> {
                row.forEach(channel -> {
                    if (channel instanceof ControlRodChannel) {
                        ((ControlRodChannel) channel).setAutoState(1);
                        ((ControlRodChannel) channel).setState(1);
                        ((ControlRodChannel) channel).setScram(tripped);
                    }
                });
            });
            channels.forEach(channel -> {
                channel.setFastScram(false);
            });
        }
        
        public boolean isTripped() {
            return tripped;
        }
        
        public boolean getSequenceBlock() {
            return sequenceLock;
        }

        /**
         * 只刷新顺序闭锁，不做 BSM 判据（2026-11-07）。
         *
         * <p>闭锁状态原来只在 {@code update()} 里刷新，而 {@code update()} 只在
         * 「自动 AZ 打开」或「FASR 已跳闸」时才被调用。装载标准工况会先关掉自动 AZ，
         * 于是冷启动那几百毫秒（211 根棒全插）算出来的 {@code sequenceLock = true}
         * 就永远冻在那里 —— 即便 AZ-5 组已经全提到 0，АР 仍然只能插棒不能提棒，
         * 功率被一路压到 1700 MW，最后汽机逆功率跳闸、AZ-1 停堆。</p>
         */
        void refreshSequenceLock() {
            updateSequenceLock();
        }
    }
    
    class AutomaticRodController implements Serializable {
        private double setpoint = 0.0;
        double ro, roSetpoint, thermalPower, averagePower;
        final ControlRodChannel[] linkedChannels;
        final ArrayList<ControlRodChannel> toControl = new ArrayList<>();
        private boolean enabled = false, error = false, limit = false, busy = false;
        public AutomaticRodController(ControlRodChannel[] linkedChannels) {
            this.linkedChannels = linkedChannels;
        }
        
        public void update() {
            thermalPower = core.getThermalPower();
            toControl.clear();
            toControl.addAll(Arrays.asList(linkedChannels));
            averagePower = 0;
            boolean upperLimit = false;
            boolean lowerLimit = false;
            boolean checkError = false;
            boolean checkBusy = false;
            for (ControlRodChannel channel: linkedChannels) {
                averagePower += channel.getNeutronPopulation()[0];
                if (channel.getPosition() == 0) {
                    upperLimit = true;
                } else if (channel.getPosition() == 1) {
                    lowerLimit = true;
                }
            }
            averagePower /= linkedChannels.length;
            ro = core.getReactivity();
            roSetpoint = 0 + ((setpoint - thermalPower) / 20000); 
            if (ro  > roSetpoint + 0.00005) {
                limit = lowerLimit;
                if (limit) {
                    checkError = true;
                }
                for (ControlRodChannel channel: linkedChannels) {
                    if (channel.getNeutronPopulation()[0] < averagePower * 0.95) {
                        toControl.remove(channel);
                    }
                }
                if (toControl.isEmpty()) {
                    toControl.addAll(Arrays.asList(linkedChannels));
                }
                for (ControlRodChannel rod: toControl) {
                    rod.setAutoState(2);
                    if (rod.getPosition() < 1) {
                        checkBusy = true;
                    }
                }
            } else if (ro < roSetpoint - 0.00005) {
                limit = upperLimit;
                if (fasrControl.getSequenceBlock()) {
                    error = true;
                    busy = false;
                    for (ControlRodChannel rod: linkedChannels) {
                        rod.setAutoState(1);
                    }
                    return;
                }
                for (ControlRodChannel channel: linkedChannels) {
                    if (channel.getNeutronPopulation()[0] > averagePower * 1.05) {
                        toControl.remove(channel);
                    }
                }
                if (toControl.isEmpty()) {
                    toControl.addAll(Arrays.asList(linkedChannels));
                }
                for (ControlRodChannel rod: toControl) {
                    rod.setAutoState(0);
                    if (rod.getPosition() > 0) {
                        checkBusy = true;
                    }
                }
            } else {
                limit = false;
                busy = false;
                for (ControlRodChannel rod: linkedChannels) {
                    rod.setAutoState(1);
                }
            }
            error = checkError;
            busy = checkBusy;
        }
        
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!enabled) {
                for (ControlRodChannel channel: linkedChannels) {
                    channel.setAutoState(1);
                }
            }
        }
        
        public void setSetpoint(double setpoint) {
            this.setpoint = setpoint;
        }
        
        public double getSetpoint() {
            return setpoint;
        }
        
        public boolean hasError() {
            return error;
        }
        
        public boolean onLimit() {
            return limit;
        }
        
        public boolean isBusy() {
            return busy;
        }
    }
    
    public long getSimulationTime() {
        return simulationTime;
    }

    /** 读档：恢复仿真时钟（事件日志/标题栏从正确的时刻继续）。 */
    public void setSimulationTime(long seconds) {
        simulationTime = Math.max(0L, seconds);
        timeUpdated = false;
    }
    
    public void updateSimulationTime() {
        simulationTime++;
        timeUpdated = true;
    }
    
    public void resetTimeUpdatedFlag() {
        timeUpdated = false;
    }
    
    public boolean getTimeUpdatedFlag() {
        return timeUpdated;
    }
    
    public void recordEvent(String event) {
        eventLog.add(NPPMath.formatSecondsToDaysAndTime(simulationTime, false) + "  " + event);
    }

    /**
     * 冷启动默认投运的自动与保护（2026-11-17）。
     *
     * <p>点"开始"进的是**冷态**：堆芯全插棒、0 MW、泵全停、汽机跳闸位 —— 这些都不动。
     * 这里只把"自动回路"的开关先投上，省得开局一个个去点，也避免因为自动没投而出现的
     * 二次报警（除氧器不进水、热井抽干之类）。</p>
     *
     * <p>与 {@link StandardIC} 第 6 步的差别只有两条：</p>
     * <ul>
     *   <li><b>不投自动棒控（АР）</b>：冷态全插棒，АР 一投就按 4800 MW 的设点自动提棒，
     *       等于开局替玩家升功率；</li>
     *   <li><b>不投主汽门自动</b>：冷态主蒸汽母管还没有压力，自动压力控制会把主汽门
     *       压在关闭位、和玩家手动开阀打架。等汽机冲转前再由玩家自己投。</li>
     * </ul>
     *
     * <p>自动 AZ（保护）本来就默认投入，这里再显式写一次，防止读档/旧状态把它带成关闭。</p>
     */
    void applyColdStartDefaults() {
        dearatorWaterAndMakeupControl.forEach(c -> c.setEnabled(true));
        dearatorWaterControl.forEach(c -> c.setEnabled(true));
        dearatorMakeupControl.forEach(c -> c.setEnabled(true));
        dearatorPressureControl.forEach(c -> c.setEnabled(true));
        mainFeederControl.forEach(c -> c.setEnabled(true));
        auxFeederControl.forEach(c -> c.setEnabled(true));
        condenserWaterLevelControl.forEach(c -> c.setEnabled(true));
        azAutoEnabled = true;
    }
    
    class FluidAutomaticRodController implements Serializable {
    private double setpoint = 0.0;
    double ro, roSetpoint, thermalPower, averagePower;
    final ArrayList<ControlRodChannel> linkedChannels = new ArrayList<>();
    final ArrayList<ControlRodChannel> toControl = new ArrayList<>();
    private final boolean[] enabled = {false, false, false}; //{lar, 1ar, 2ar}
    private final boolean[] error = {false, false, false};
    private final boolean[] limit = {false, false, false};
    private final boolean[] busy = {false, false, false};

        public void update() {
            thermalPower = core.getThermalPower();
            toControl.clear();
            toControl.addAll(linkedChannels);
            averagePower = 0;
            boolean upperLimit[] = {false, false, false};
            boolean lowerLimit[] = {false, false, false};
            boolean checkError[] = {false, false, false};
            boolean checkBusy[] = {false, false, false};
            boolean fineControl = true;
            for (ControlRodChannel channel: linkedChannels) {
                averagePower += channel.getNeutronPopulation()[0];
                if (channel.getPosition() == 0) {
                    if (ar1.contains(channel)) {
                        upperLimit[1] = true;
                    } else if (ar2.contains(channel)) {
                        upperLimit[2] = true;
                    } else {
                        upperLimit[0] = true;
                    }
                } else if (channel.getPosition() == 1) {
                    if (ar1.contains(channel)) {
                        lowerLimit[1] = true;
                    } else if (ar2.contains(channel)) {
                        lowerLimit[2] = true;
                    } else {
                        lowerLimit[0] = true;
                    }
                }
            }
            averagePower /= linkedChannels.size();
            ro = core.getReactivity();
            /* ---- 功率死区 + 温和增益（2026-11-07）----
             *
             * <p>旧式 {@code roSetpoint = (setpoint − power) / 50000}：偏差 1 600 MW 就要
             * 0.032 的反应性（占 RBMK 总反应性的大半），棒被一路提到接近全提；
             * 而功率对棒位有十几秒延迟，等功率回到设定时棒已经过头，接着继续冲高
             * → AZ-1K「功率 &gt; 120 % / 周期 &lt; 10 s」停堆（实测 t=90 s）。</p>
             *
             * <p>现在：±1 %（且不小于 40 MW）死区内棒不动；死区外增益降到 1/200000，
             * 反应性请求限幅 ±0.5 %，动作幅度小一个量级，不会再"一冲到底"。</p>
             */
            /* 纯功率误差（2026-11-07 实测：加压力前馈反而更差——压力扫过时的
             * 折算值和功率偏差互相打架，70 s 就冲到 AZ-3。先保持最简形式。） */
            double powerError = setpoint - thermalPower;
            /* 死区放到 ±3 %（2026-11-07）：汽包压力回升期（4.5→6.9 MPa）空泡塌陷会持续
             * 引入负反应性，АР 为了追功率会把棒从 0.78 一路提到 0.26（52 % 行程）；
             * 等压力稳住，这 52 % 的盈余反应性就变成功率冲高 → AZ-1K。
             * ±3 %（±144 MW）先让棒停手，避免在压力瞬变里堆反应性。 */
            if (Math.abs(powerError) <= Math.max(150.0, setpoint * 0.03)) {
                for (int i = 0; i < 3; i++) {
                    busy[i] = false;
                    limit[i] = false;
                    error[i] = false;
                }
                for (ControlRodChannel rod : linkedChannels) {
                    rod.setAutoState(1);
                }
                return;
            }
            /* 反应性目标只用于界面显示（Selsyn/面板）；真正的动作判据改成
             * **功率误差的符号**（2026-11-07）。
             *
             * <p>原因是"反应性闭环"本质上带积分：只要 |ro − roSetpoint| 有意义，
             * 棒就一直动，而功率对棒位的响应有十几秒延迟，必定过冲。实测在
             * 6138 → 4648 → 2876 MW 之间来回振荡，最后把汽机拖到逆功率。</p>
             *
             * <p>现在：功率低于设定 → 提棒，高于设定 → 插棒；偏差 &gt; 3 % 用快速档
             * （RodSpeed 全行程 17.5 s），3 % 以内用 0.5 %/s 慢档，权重都在"功率"上，
             * 不再被反应性中间量带偏。</p> */
            roSetpoint = Math.max(-0.005, Math.min(0.005, powerError / 200000.0));
            boolean raisePower = powerError > 0;                       // 功率偏低 → 该提棒
            /* 快速档门槛 30 %（2026-11-07）：单回路下功率对棒位极敏感，只要走 17.5 s
             * 全行程的快速档，1 600 MW 的偏差就能把棒从 0.36 一路插到 1.00、功率砸到 3 187。
             * 现在只有超过 30 %（1 440 MW）的极端偏差才用快速档，平时一律 0.36 %/s 慢调。 */
            boolean coarseStep = Math.abs(powerError) > Math.max(480.0, setpoint * 0.10);
            if (!raisePower) {                 // 功率高于设定 → 插棒
                if (coarseStep) {
                    fineControl = false;
                }
                for (int i = 0; i < 3; i++) {
                    limit[i] = lowerLimit[i];
                    if (limit[i]) {
                        checkError[i] = true;
                    }
                }
                if (!(limit[0] || limit[1] || limit[2])) {
                    for (ControlRodChannel channel: linkedChannels) {
                        if (channel.getNeutronPopulation()[0] < averagePower * 0.99) {
                            toControl.remove(channel);
                            channel.setAutoState(0);
                        }
                    }
                }
                if (toControl.isEmpty()) {
                    toControl.addAll(linkedChannels);
                }
                for (ControlRodChannel rod: toControl) {
                    if (rod.getPosition() < 1) {
                        if (fineControl) {
                            rod.setAutoState(1);
                            rod.setPosition(rod.getPosition() + 0.00018f);
                            if (rod.getPosition() > 1) {
                                rod.setPosition(1);
                            }
                        } else {
                            rod.setAutoState(2);
                        }
                        if (ar1.contains(rod)) {
                            checkBusy[1] = true;
                        } else if (ar2.contains(rod)) {
                            checkBusy[2] = true;
                        } else {
                            checkBusy[0] = true;
                        }
                    }
                }
            } else {                           // 功率低于设定 → 提棒
                if (coarseStep) {
                    fineControl = false;
                }
                for (int i = 0; i < 3; i++) {
                    limit[i] = upperLimit[i];
                }
                if (fasrControl.getSequenceBlock()) {
                    for (int i = 0; i < 3; i++) {
                        error[i] = true;
                        busy[i] = false;
                    }
                    for (ControlRodChannel rod: linkedChannels) {
                        rod.setAutoState(1);
                    }
                    return;
                }
                if (!(limit[0] || limit[1] || limit[2])) {
                    for (ControlRodChannel channel: linkedChannels) {
                        if (channel.getNeutronPopulation()[0] > averagePower * 1.01) {
                            toControl.remove(channel);
                            channel.setAutoState(2);
                        }
                    }
                }
                if (toControl.isEmpty()) {
                    toControl.addAll(linkedChannels);
                }
                for (ControlRodChannel rod: toControl) {
                    if (rod.getPosition() > 0) {
                        if (fineControl) {
                            rod.setAutoState(1);
                            rod.setPosition(rod.getPosition() - 0.00018f);
                            if (rod.getPosition() < 0) {
                                rod.setPosition(0);
                            }
                        } else {
                            rod.setAutoState(0);
                        }
                        if (ar1.contains(rod)) {
                            checkBusy[1] = true;
                        } else if (ar2.contains(rod)) {
                            checkBusy[2] = true;
                        } else {
                            checkBusy[0] = true;
                        }
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                    error[i] = checkError[i];
                    busy[i] = checkBusy[i];
                }
        }
        
        public void setSetpoint(double setpoint) {
            this.setpoint = setpoint;
        }
        
        public double getSetpoint() {
            return setpoint;
        }
        
        public boolean[] isEnabled() {
            return enabled;
        }
        
        public boolean[] hasError() {
            return error;
        }
        
        public boolean[] onLimit() {
            return limit;
        }
        
        public boolean[] isBusy() {
            return busy;
        }
        
        public void enableLAR() {
            if (enabled[0]) {
                return;
            }
            linkedChannels.addAll(lar);
            enabled[0] = true;
        }
        
        public void disableLar() {
            if(!enabled[0]) {
                return;
            }
            lar.forEach(channel -> {
                channel.setAutoState(1);
            });
            linkedChannels.removeAll(lar);
            enabled[0] = false;
        }
        
        public void enable1AR() {
            if (enabled[1]) {
                return;
            }
            linkedChannels.addAll(ar1);
            enabled[1] = true;
        }
        
        public void disable1AR() {
            if (!enabled[1]) {
                return;
            }
            ar1.forEach(channel -> {
                channel.setAutoState(1);
            });
            linkedChannels.removeAll(ar1);
            enabled[1] = false;
        }
        
        public void enable2AR() {
            if (enabled[2]) {
                return;
            }
            linkedChannels.addAll(ar2);
            enabled[2] = true;
        }
        
        public void disable2AR() {
            if (!enabled[2]) {
                return;
            }
            ar2.forEach(channel -> {
                channel.setAutoState(1);
            });
            linkedChannels.removeAll(ar2);
            enabled[2] = false;
        }
    }
}
