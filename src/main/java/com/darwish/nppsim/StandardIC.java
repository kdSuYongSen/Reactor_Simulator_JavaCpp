package com.darwish.nppsim;

import java.util.ArrayList;

/**
 * 标准初始工况（Standard Initial Condition，2026-11-02）。
 *
 * <p>"Load Standard IC" 按钮背后就是这里：把新建的（冷态、全停）电厂一次整定到
 * **100% 额定稳态**，操作员点开始后直接进入满功率运行，不必再手动干这几十分钟：</p>
 *
 * <table border="1">
 *   <caption>整定目标</caption>
 *   <tr><th>子系统</th><th>目标状态</th></tr>
 *   <tr><td>堆芯</td><td>4800 MW 热功率；各节点 n=1、ρ=0、氙碘平衡（内核 resetToNominal）；平均棒位 0.25（= 内核 P_ROD_NOMINAL 0.75），AZ-5 组全提</td></tr>
 *   <tr><td>一回路</td><td>汽包 6.9 MPa / 284 °C / 水位 0；8 台主泵全速（≈12,000 kg/s）；压力母管 284 °C 额定装量</td></tr>
 *   <tr><td>二回路</td><td>汽水分离器 6.9 MPa；<b>主蒸汽母管 6.9 MPa</b>；除氧器 153 °C；凝汽器热井 700 t；汽机并网 3000 rpm</td></tr>
 *   <tr><td>控制</td><td>凝结水/给水/ADMS/循环水泵全投；主汽门 45%；给水与除氧器水位自动、凝汽器水位自动、主汽压力自动投入</td></tr>
 * </table>
 *
 * <p>数值取自 RBMK-1500 额定参数：6.9 MPa 饱和温度 284 °C、给水 165 °C、
 * 二次侧额定产汽约 2,300 kg/s（4800 MW ÷ ≈2,070 kJ/kg），单台汽机额定汽量 900 kg/s
 * （≈760 MWe，两台合计 ≈1,520 MWe）。</p>
 *
 * <p>注意：这里只设"状态"，不设"功率覆盖"。堆芯功率来自动力学内核的额定平衡态；
 * 若 DLL 加载失败（安全模式），{@link NPPSim.core#resetToNominal()} 会返回 false，
 * 此时仍会把一/二回路整定到热态，只是堆芯保持在停堆零功率。</p>
 */
final class StandardIC {

    /** 汽包/一回路额定压力 MPa（6.9 MPa 饱和温度 284 °C） */
    static final double DRUM_PRESSURE_MPA = 6.90;
    /** 一回路额定饱和温度 °C（汽包/蒸汽，6.9 MPa 饱和） */
    static final double DRUM_TEMP_C = 284.0;
    /** 堆芯额定进口温度 °C（比饱和温度低约 14 K，保证通道有欠热段） */
    static final double CORE_INLET_C = 270.0;
    /**
     * 主蒸汽母管（二次侧）额定压力 MPa。
     *
     * <p>取 4.0 MPa，与 {@link Separator#PRESSURE_SETPOINT} 一致——这是本工程二回路的设计压力。</p>
     */
    static final double STEAM_HEADER_MPA = DRUM_PRESSURE_MPA;
    /** 除氧器额定水温 °C（0.53 MPa 饱和） */
    static final double DEAERATOR_TEMP_C = 153.0;
    /**
     * 主汽门初始开度。
     *
     * <p>取 0.20 而不是 0.45：装载瞬间二次侧只有几百 kg/s 的产汽能力（给水回路还在建立），
     * 主汽门开太大（0.45 ≈ 900 kg/s）会把主蒸汽母管抽空 → 母管压力跌 → 换热器抽汽变多
     * → 一回路汽包被抽到 5 MPa 以下 → 汽机"低汽包压力"跳闸、堆芯功率跟着掉。
     * 0.20 大约对应 200~300 kg/s，正好匹配初期产汽量；母管压力上来后，
     * 主汽压力自动控制会把阀继续开大（每台机一个控制器）。</p>
     */
    static final float TG_INLET_POSITION = 0.05f;
    /** 额定热功率 MW（自动棒控的目标值） */
    static final double RATED_POWER_MW = 4800.0;
    /** 额定运行的平均棒位（Java 口径：0=全提 1=全插）；内核 P_ROD_NOMINAL=0.75 与之对应 */
    static final double RATED_ROD_INSERTION = 0.25;

    private StandardIC() {
    }

    /**
     * 把当前电厂整定到标准初始工况。
     *
     * @return true = 全部整定成功；false = 堆芯内核不可用（安全模式），一/二回路仍已整定
     */
    static boolean apply() {
        if (NPPSim.core == null || NPPSim.mcc == null) {
            return false;
        }

        /* ---- 0. 第一件事：关掉自动 AZ（2026-11-05）----
         *
         * <p>这一步必须排在最前面。原来它排在第 250 行左右，而前面几步里
         * {@code resetToNominal()} 已经把堆芯重新提到 4800 MW —— 从"提棒"到"关 AZ"
         * 之间的那几毫秒，AZ 还是投着的，装载瞬态的功率冲高（实测 7 437 MW、
         * 周期 0.2 s）会立刻被 AZ-1K 按"周期 &lt; 10 s"抓住并 SCRAM 全堆，
         * 之后堆功率就压在几百 MW 上不来了。</p>
         */
        if (NPPSim.autoControl != null) {
            NPPSim.autoControl.azAutoEnabled = false;
        }

        // ================= 1. 控制棒：AZ-5 全提，工作棒按平均棒位折算 =================
        /* 内核对外的棒位是 211 根棒的**平均值**（Core.syncRodInsertion）。
         * 额定态要求平均值 0.25；AZ-5 组在功率运行时本来就是全提的，
         * 所以让 24 根 AZ-5 取 0，其余 187 根取 0.25×211/187 ≈ 0.282。
         * 同时清掉 scram/fastScram —— 否则 update() 里会把棒一路往下插。 */
        int fasr = 0, others = 0;
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (int i = 0; i < row.size(); i++) {
                Channel c = row.get(i);
                if (!(c instanceof ControlRodChannel)) {
                    continue;
                }
                ControlRodChannel rod = (ControlRodChannel) c;
                rod.setScram(false);
                rod.setFastScram(false);
                rod.setState(1);
                rod.setAutoState(1);
                if (c instanceof FASRChannel) {
                    rod.setPosition(0.0f);      // AZ-5：全提
                    fasr++;
                } else {
                    others++;
                }
            }
        }
        float workPos = others > 0
                ? (float) Math.min(1.0, RATED_ROD_INSERTION * (fasr + others) / others)
                : (float) RATED_ROD_INSERTION;
        for (ArrayList<Channel> row : NPPSim.core.coreArray) {
            for (int i = 0; i < row.size(); i++) {
                Channel c = row.get(i);
                if (c instanceof ControlRodChannel && !(c instanceof FASRChannel)) {
                    ((ControlRodChannel) c).setPosition(workPos);
                }
            }
        }

        // ================= 2. 堆芯：复位到额定平衡态（4800 MW） =================
        // 内部会先把上面的棒位汇总进内核，再 reset → 不会出现"瞬间提棒"注入反应性
        /* 复位全部 AZ 保护（2026-11-05）。
         *
         * <p>实测：不加这一句，装载后 t=0.3 s 时 {@code az1Control.isTripped()} 已经是 true ——
         * 冷启动那几百毫秒里（汽包 0.1 MPa、通道 20 °C 冷水）AZ-1K 按"反应性/周期"动作过一次，
         * 全堆棒被插到底并锁存。装载后堆功率从 4 555 MW 一路掉到 770 MW、再也回不去。
         * 加这一句后 AZ-1K 保持 false、功率稳定在 4 800 MW。</p>
         */
        if (NPPSim.autoControl != null) {
            NPPSim.autoControl.resetAllAz();
        }
        boolean coreOk = NPPSim.core.resetToNominal();

        // ================= 3. 一回路：汽包 284 °C 饱和 + 通道 270 °C 进口 + 8 台主泵全速 =================
        /* 先按 **堆芯进口温度** 整定（通道水温/饱和压力 + 母管装量），
         * 再把两台汽包单独复位到 **284 °C 饱和**（6.9 MPa）。
         * 不能直接用 setWaterTemp(284)：那会把通道水也设成饱和温度，
         * 通道没有欠热段，一回路循环流量起不来（实测只有约 4,900 kg/s）。 */
        NPPSim.mcc.setWaterTemp(CORE_INLET_C);
        NPPSim.mcc.drum1.resetToTemperature(DRUM_TEMP_C);
        NPPSim.mcc.drum2.resetToTemperature(DRUM_TEMP_C);
        /* 压力母管/下降段保持 270 °C（不要跟着写成 284）：
         * 真实 RBMK 的下降段水温比汽包饱和温度低十几度（给水混入 + 静压），
         * 通道就是靠这十几度过冷段才建立得起循环流量。写成 284 会让通道一进口就沸腾，
         * 一回路流量卡在 6,600 kg/s 左右、堆芯功率被空泡压到 800 MW。 */
        /* 主循环泵也必须**立刻**到额定转速（2026-11-02）：
         * MCCPump 的加速时间 18 s，只设 setSetPoint(1) 的话头 20 s 里一回路流量只有额定的
         * 四到六成，1661 根通道因此欠冷/局部干涸 → Core 把 dryoutFraction 推给内核
         * → 内核按"传热恶化"处理 → 功率从 4800 MW 一路掉到 500~1500 MW，且**每次都不一样**
         * （同一份代码两次跑出 4592 和 508 MW，正是这个瞬态不稳造成的）。 */
        for (Pump p : NPPSim.mcc.mcp) {
            p.setActive(true);
            p.setSetPoint(1.0f);
            p.setRPM(p.ratedRPM);
        }

        // ================= 4. 二回路热态：分离器 + 主蒸汽母管 + 除氧器 =================
        if (NPPSim.separators != null) {
            for (Separator s : NPPSim.separators) {
                if (s != null) {
                    // 分离器压力 next tick 由汽量平衡重算，这里给额定初值
                    s.pressureMPa = STEAM_HEADER_MPA;
                }
            }
        }
        if (NPPSim.steamPiping != null) {
            /* 主蒸汽母管（分离器 → 汽机主汽门/除氧器抽汽口）的额定压力。
             * 运行时它的压力由各分离器压力取最高值给出，这里设的是初值/界面读数起点。 */
            NPPSim.steamPiping.pressure = STEAM_HEADER_MPA;
        }
        if (NPPSim.dearators != null) {
            for (Dearator d : NPPSim.dearators) {
                if (d != null) {
                    d.setWaterTemp(DEAERATOR_TEMP_C);   // 0.53 MPa 饱和，水量按额定容积
                }
            }
        }

        // ================= 5. 泵与阀门 =================
        startPumps(NPPSim.condensate1A);
        startPumps(NPPSim.condensate2A);
        startPumps(NPPSim.condensate1B);
        startPumps(NPPSim.condensate2B);
        startPumps(NPPSim.mainFeedwaterPumps);
        startPumps(NPPSim.auxFeedwaterPumps);
        if (NPPSim.pcs != null) {
            startPumps(NPPSim.pcs.admsPumps);
        }
        startCondenserPumps(NPPSim.tg1);
        startCondenserPumps(NPPSim.tg2);

        // 主汽门：开到额定开度并置为"停止"位，避免自动压力控制立刻把阀关死
        setSteamValves(NPPSim.TG1InletValves);
        setSteamValves(NPPSim.TG2InletValves);

        /* 水位控制的设定值要抬一点（2026-11-02）：
         * 这些控制器是"偏差 > 1% 才动作"的比例+微分律，整定那一刻水位正好在设定值 0 上
         * （误差 0、变化率 0）→ 阀门一动不动停在 0 → 除氧器没进水 → 给水泵无源
         * → 混合器无水 → 换热器管侧无给水 → 二次侧不产汽。
         * 把各水位设定值抬到额定以上一点点，控制器就有了工作偏差，阀门会开到水位到位为止。
         * （试过"直接把阀门预开到固定开度"，反而把汽包压力拉下来了——固定开度不受控。） */
        setLevelSetpoints(NPPSim.autoControl, DEAERATOR_LEVEL_SETPOINT, FEEDER_LEVEL_SETPOINT);

        /* 抽真空 + 解除汽机跳闸（2026-11-03）。
         *
         * 凝汽器冷态是**常压**（汽空间按空气算），必须抽掉空气才有真空；
         * 而 TG 的 reset() 判据里就有"凝汽器压力 < 0.030 MPa"，不抽真空永远解不了跳闸，
         * 界面一直显示汽机跳闸。同时真空决定可用焓降：
         *   0.101 MPa（无真空）→ 排汽按 100 °C 算，w 只有约 530 kJ/kg；
         *   0.005 MPa（正常真空）→ 排汽 33 °C，w 约 840 kJ/kg。
         * 标准工况直接按"机组已在正常运行"处理：把凝汽器汽空间的空气抽净，
         * 并把真空泵投入（否则漏入空气会让真空慢慢掉）。 */
        evacuateCondensers(NPPSim.tg1);
        evacuateCondensers(NPPSim.tg2);
        if (NPPSim.vacuumPump1 != null) {
            NPPSim.vacuumPump1.setRunning(true);
        }
        if (NPPSim.vacuumPump2 != null) {
            NPPSim.vacuumPump2.setRunning(true);
        }

        // 汽机并网（rpm=3000 + synced），否则 getGeneratorLoad() 恒为 0
        if (NPPSim.tg1 != null) {
            NPPSim.tg1.forceReset();   // 无条件解除跳闸（真空刚抽完，压力还没重算）
            NPPSim.tg1.forceSync();
            NPPSim.tg1.suppressStartupProtection(1200);   // 60 s 启动旁路，等蒸汽/真空建立
        }
        if (NPPSim.tg2 != null) {
            NPPSim.tg2.forceReset();
            NPPSim.tg2.forceSync();
            NPPSim.tg2.suppressStartupProtection(1200);
        }

        // ================= 6. 自动控制 =================
        /* 这些自动默认是关的（AutoControl 里只有 SDV/MSV 默认投入）。
         * 不投的话：除氧器不进水 → 给水泵无源 → 二次侧无给水 → 不产汽 → 汽机无出力。 */
        AutoControl ac = NPPSim.autoControl;
        if (ac != null) {
            ac.dearatorWaterAndMakeupControl.forEach(c -> c.setEnabled(true));
            ac.dearatorWaterControl.forEach(c -> c.setEnabled(true));
            ac.dearatorMakeupControl.forEach(c -> c.setEnabled(true));
            ac.dearatorPressureControl.forEach(c -> c.setEnabled(true));
            ac.mainFeederControl.forEach(c -> c.setEnabled(true));
            ac.auxFeederControl.forEach(c -> c.setEnabled(true));
            ac.condenserWaterLevelControl.forEach(c -> c.setEnabled(true));
            ac.tgValveControl.forEach(c -> c.setEnabled(true));
            /* 自动棒控（АР）：把目标热功率设成 4800 MW 并投入三组。
             * 刚整定的头一两分钟里，一回路循环流量还在从"泵刚起转"往额定爬
             * （模型里流量由各等容节点的容积偏差逐拍传递，约需 1~2 min 才到 12,000 kg/s），
             * 期间通道欠冷、空泡为负 → 堆芯功率会自己往下掉。真实机组这时靠 АР 自动提棒保功率，
             * 这里也把它投上，才能让"标准初始工况"真的稳在 4800 MW。
             * 不投的话实测功率会从 4800 一路掉到 600 MW。 */
            ac.automaticRodController.setSetpoint(RATED_POWER_MW);
            ac.automaticRodController.enableLAR();
            ac.automaticRodController.enable1AR();
            ac.automaticRodController.enable2AR();
            /* 装载/整定期间**关掉自动 AZ**（2026-11-02）。
             * 整定后的头几十秒是个瞬态：一回路流量、二次侧水位、除氧器压力都在建立过程中，
             * 功率/周期/水位都会瞬时越限。AZ-2（中子周期 < 20 s）和 AZ-1（高功率/高水位）
             * 一旦动作就是 SCRAM 全棒插到底，功率随即掉到几百 MW、再也回不来 ——
             * 实测同一份代码两次跑出 4592 和 508 MW，差别就在这里。
             * 真实现场装载初始工况时也是把保护切到"旁路"，投运稳定后再投保护。
             * 保护会在额定流量保护期结束（Core 里 icRatedFlowTicks 归零）时自动恢复。 */
            ac.azAutoEnabled = false;
            ac.recordEvent(Localization.t("Standard IC loaded") + "：4800 MW / "
                    + String.format("%.2f", DRUM_PRESSURE_MPA) + " MPa");
        }

        // ================= 7. 电气系统 =================
        /* 额定接线：220 kV 并网、110 kV 备用投入、两段 6.6 kV 由厂用分支供电、
         * 应急段/380 V/直流全部正常，柴油机停、蓄电池满。
         * 不重置的话，从冷态（发电机未并网）装载时母线判据会沿用上一状态，
         * 第一帧就可能把主泵按"母线失电"跳掉。 */
        if (NPPSim.grid != null) {
            NPPSim.grid.resetToNominal();
        }
        return coreOk;
    }

    /**
     * 让一组泵**立刻**到额定转速。
     *
     * <p>只设 setSetPoint(1) 的话，泵要从 0 加速到额定 —— 主泵加速时间 18 s，
     * 这 18 s 里一回路流量只有额定的三成，堆芯功率会被空泡/多普勒压下来
     * （实测从 4800 掉到 3,500 MW）。标准初始工况直接置转速，让第一帧就是额定流量。</p>
     */
    private static void startPumps(ArrayList<Pump> pumps) {
        if (pumps == null) {
            return;
        }
        for (int i = 0; i < pumps.size(); i++) {
            Pump p = pumps.get(i);
            if (p != null) {
                p.setActive(true);
                p.setSetPoint(1.0f);
                p.setRPM(p.ratedRPM);
                /* 泵的出口隔离阀（dischargeValve）**直接全开**（2026-11-02）。
                 * 这个阀在模型里同时是"泵的出口节流"，而 currentHead 会乘上它的开度：
                 * 阀门只开到 20% 时，给水泵的 8.9 MPa 关死扬程只剩 1.5 MPa，
                 * 而换热器管侧是 4 MPa —— 给水阀根本没有压差把水推进去，
                 * 二次侧永远不产汽。真实机组运行中出口隔离阀是全开的，
                 * 流量靠给水调节阀控制，这里直接把隔离阀打开。
                 * （凝结水增压泵 1B/2B 的出口阀同时被热井水位控制器使用，会被它接管。） */
                p.dischargeValve.setPosition(1.0f);
            }
        }
    }

    /** 把一台机的 4 只凝汽器汽空间里的空气全部抽走（＝机组已在正常真空下运行） */
    private static void evacuateCondensers(TG tg) {
        if (tg == null || tg.condenser == null) {
            return;
        }
        for (Condenser c : tg.condenser.units) {
            if (c != null) {
                c.removeGas(c.getGasMassKg());
            }
        }
    }

    private static void startCondenserPumps(TG tg) {
        if (tg == null || tg.condenser == null) {
            return;
        }
        for (Condenser c : tg.condenser.units) {
            c.condenserPump.setActive(true);
            c.condenserPump.setSetPoint(1.0f);
            c.condenserPump.setRPM(c.condenserPump.ratedRPM);   // 循环水一上来就是额定流量
        }
    }

    /** 除氧器水位设定值 %（相对额定） */
    static final double DEAERATOR_LEVEL_SETPOINT = 5.0;
    /** 换热器管侧（给水）水位设定值 % */
    static final double FEEDER_LEVEL_SETPOINT = 2.0;
    /**
     * 凝汽器热井水位设定值 %（负 = 允许比额定低）。
     *
     * <p>这个回路控的是**凝结水增压泵的出口阀**，即热井的出口通道。整定那一刻热井正好在额定
     * （0%），若设定值也取 0，控制器落在死区里阀门停在 0（全关）——凝结水一滴也送不出去，
     * 凝结水联箱建不起压，除氧器进水阀压差为负，整条二回路从第一级就断了。</p>
     *
     * <p>取 −5%：阀门一开始就全开，凝结水正常循环；热井掉到 −5% 附近时控制器才开始收阀
     * （±1% 死区 + 微分微调）。700 t 的热井给 5% 余量 ≈ 35 t，够控制器工作。</p>
     */
    static final double CONDENSER_LEVEL_SETPOINT = -5.0;

    /** 给各水位控制回路设置工作设定值 */
    private static void setLevelSetpoints(AutoControl ac, double deaerator, double feeder) {
        if (ac == null) {
            return;
        }
        ac.dearatorWaterAndMakeupControl.forEach(c -> c.setSetpoint(deaerator));
        ac.dearatorWaterControl.forEach(c -> c.setSetpoint(deaerator));
        ac.dearatorMakeupControl.forEach(c -> c.setSetpoint(deaerator));
        ac.mainFeederControl.forEach(c -> c.setSetpoint(feeder));
        ac.auxFeederControl.forEach(c -> c.setSetpoint(feeder));
        ac.condenserWaterLevelControl.forEach(c -> c.setSetpoint(CONDENSER_LEVEL_SETPOINT));
    }

    /** 把一组水阀摆到指定开度（停在"停止"位，随后的自动控制接着调） */
    private static void preopen(ArrayList<WaterValve> valves, double position) {
        if (valves == null) {
            return;
        }
        float p = (float) Math.max(0.0, Math.min(1.0, position));
        for (WaterValve v : valves) {
            if (v != null) {
                v.setAutoState(1);
                v.setState(1);
                v.setPosition(p);
            }
        }
    }

    private static void setSteamValves(ArrayList<SteamValve> valves) {
        if (valves == null) {
            return;
        }
        for (SteamValve v : valves) {
            if (v != null) {
                v.setAutoState(1);              // 停在手动位，防止自动压力控制立刻关阀
                v.setState(1);
                v.setPosition(TG_INLET_POSITION);
            }
        }
    }
}
