package com.darwish.nppsim;

import static com.darwish.nppsim.NPPSim.TG1InletValves;
import static com.darwish.nppsim.NPPSim.TG2InletValves;
import static com.darwish.nppsim.NPPSim.atmosphere;
import static com.darwish.nppsim.NPPSim.autoControl;
import static com.darwish.nppsim.NPPSim.core;
import static com.darwish.nppsim.NPPSim.mcc;
import static com.darwish.nppsim.NPPSim.tg1;
import static com.darwish.nppsim.NPPSim.tg2;
import java.io.Serializable;

class TG extends WaterSteamComponent implements Connectable {
    /** 本机的 4 台凝汽器 */
    final Condenser[] condensers;
    /** 凝汽器组（对外当作一个凝汽器用：压力/温度/水位取平均值） */
    final CondenserBank condenser;
    private double lastStepSteamInflow = 0.0;
    private double casingTemp, turbineTemp, load;
    private double steamInletPressure = 0.0, steamOutLetPressure = 0.0;
    private double steamInflowTemperature = 20.0, steamOutflowTemperature = 20.0;
    /** 转速 rpm（包内可见：保护逻辑与自检工具需要直接置值做超速试验） */
    float rpm = 0.0f;
    private boolean synced = false, tripped = true, reversePower = false;
    private static float gridPhase = 180.0f;
    private float genPhase = 0.0f, genAlignment;
    private boolean isTG1 = false, reversePowerRecorded = false;
    /** 本机保护（真实保护清单，见 TurbineProtection） */
    final TurbineProtection protection = new TurbineProtection();

    /* ================================================================
     * 润滑油系统（2026-11-16 新增）
     *
     * 与应急柴油机同一套做法：A = 交流主油泵（厂用 380 V 供电），
     * B = 直流事故油泵（220 V 直流母线供电）。母管油压由"泵台数 +
     * 主轴泵残压"决定，油压低于 0.8 bar 持续 3 s 由 TurbineProtection 跳机。
     *
     * 主轴泵（轴承润滑油泵）在额定转速附近只补一小部分压力：足够维持惰走，
     * 但不足以替代 A/B 泵 —— 这样"停错泵会跳机"在满负荷时也成立。
     * ================================================================ */
    /** 主油泵 A（交流）开关位置 */
    private boolean lubePumpA = true;
    /** 事故油泵 B（直流）开关位置 */
    private boolean lubePumpB = false;
    /** B 泵低油压自启联锁 */
    private boolean lubeStandbyAuto = true;
    /** 单泵额定油压 bar */
    static final double LUBE_RATED_BAR = 2.6;
    /** B 泵联锁动作油压 bar（低于此值自动启事故泵） */
    static final double LUBE_STANDBY_BAR = 1.6;
    /** 母管油压 bar（表压） */
    private double lubePress = LUBE_RATED_BAR;
    /** 润滑油温 °C */
    private double lubeOilTemp = 42.0;
    /** 泵实际在转（含电源可用性判断，指示灯用它） */
    private boolean lubePumpARunning, lubePumpBRunning;
    /** 联锁自启只报一次事件，油压恢复后复位 */
    private boolean lubeStandbyAnnounced = false;
    /** 老存档兼容：旧档没有油泵字段，第一次 update 补一个"主泵已投"的初态 */
    private boolean lubeInitialised = false;
    /**
     * 装载标准初始工况后的**启动旁路**（tick 数，2026-11-03）。
     *
     * <p>装载工况是"一步到位"并网：rpm 立刻置 3000、synced 置 true，可主蒸汽要十几秒
     * 才从分离器走到汽轮机，凝汽器压力也要下一帧才按抽完真空重算。这段时间里
     * 三条按稳态设计的保护都会误动作：</p>
     * <ul>
     *   <li>逆功率：进汽 0 而空载汽耗按 20 kg/s 算 → 功率 −16 MWe → 7 s 后跳机；</li>
     *   <li>低真空：凝汽器压力还是上一帧的 0.101 MPa → 立刻判"真空低"跳机；</li>
     *   <li>低汽包压力：给水回路还没建立，汽包在几秒内可能瞬时低于 5 MPa。</li>
     * </ul>
     * <p>真实现场是"先抽真空 → 冲转 → 并网 → 加负荷"的连续过程，不存在这个台阶。
     * 所以装载时给 60 s 旁路，之后保护照常。</p>
     */
    /** 装载标准工况用：给启动保护一段旁路时间（tick 数，50 ms/tick） */
    void suppressStartupProtection(int ticks) {
        protection.suppressStartupProtection(ticks);
    }

    /* ================================================================
     * 出力/转速标定（2026-10-17 由经验线性式改为按焓降计算）
     *
     * 旧式  load = ((steamInflow − 7.0) / (61.1 − 7.0)) × 750
     * 里的 61.1 kg/step(=1222 kg/s) 是"单机满负荷汽耗"，7.0 kg/step(=140 kg/s)
     * 是空载汽耗。全厂蒸汽改走除氧器后，主汽门定径让单机只能过约 900 kg/s，
     * 上式永远到不了 750 MW，且与真空/汽温无关。
     *
     * 现在直接按能量算：
     *   电功率 = (主蒸汽流量 − 空载汽耗) × 可用焓降 × 发电机效率
     * 可用焓降 = (h_g(主汽) − [h_f + x·h_fg](排汽压力)) × 等熵效率
     * 这样凝汽器真空恶化、主汽温度变化都会如实反映到出力上。
     * ================================================================ */
    /** 等熵效率（饱和汽 0.6 MPa → 5~7 kPa 的大型汽轮机 0.85~0.90） */
    private static final double ISENTROPIC_EFFICIENCY = 0.88;
    /** 发电机效率 */
    private static final double GENERATOR_EFFICIENCY = 0.98;
    /* 2026-10-19：去掉固定的"排汽干度 0.80"，改用蒸汽表的熵列做真实等熵膨胀。
     * 固定干度会让"提高主汽压力"几乎不增加出力（0.7 与 6 MPa 的 h_g 只差 26 kJ/kg），
     * 而真实循环里压力越高、排汽干度越低、单位质量功越大。 */
    /** 空载汽耗（kg/s）：轴承摩擦、调速器、轴封漏汽（低于此流量时逆功率） */
    private static final double NO_LOAD_STEAM_FLOW_KGS = 20.0;
    /** 额定主蒸汽流量（kg/s，单台）：二回路额定产汽约 2300 kg/s，两台均分并扣除氧器抽汽后约 900 */
    private static final double RATED_STEAM_FLOW_KGS = 900.0;

    /* ---------- 除氧器抽汽口（2026-10-19） ----------
     * 主蒸汽直接进汽轮机；除氧器的加热汽来自**中压缸抽汽**，不是主汽管。
     * 抽汽口压力 0.60 MPa（除氧器 0.53 MPa，ΔP=0.07 与原来一致）。
     * 抽汽蒸汽在汽轮机里已经膨胀到 0.60 MPa，所以它的做功要计入：
     *   load = (进汽−抽汽−空载)·w(到凝汽器) + 抽汽·w(到抽汽口)
     * 这样能量是守恒的（抽汽不是"从主汽管白拿"，而是先做了功）。 */
    final ExtractionPort extractionPort = new ExtractionPort(0.60, 0.35);   // 除氧器
    final ExtractionPort lpExtractionPort = new ExtractionPort(0.15, 0.20);  // 低压加热器

    /** 主汽等熵膨胀到 pOut 的单位质量功（kJ/kg，已含等熵效率）。
     *  s_in = s_g(T_in)  →  p_out 下 x = (s_in − s_f)/s_fg  →  h_out = h_f + x·h_fg。
     *  同一个函数同时用于"到凝汽器"和"到抽汽口"，保证两段功口径一致。
     *  验证台可直接调用，与运行时是同一套计算。 */
    static double specificWorkKjPerKg(double tInC, double pOutMPa) {
        /* 汽水表可能在 NPPSim 建好之前就被调用（数值自检 TurbineCheck），
         * 那时 NPPSim.tables 还是 null，退回 Loader.tables（程序一启动就建好了）。
         * 原来只认 NPPSim.tables，自检里一律算出 0 kJ/kg。 */
        SteamTables tb = NPPSim.tables != null ? NPPSim.tables : Loader.tables;
        if (tb == null) return 0.0;
        double tIn = Math.max(1.0, Math.min(372.0, tInC));
        double pOut = Math.max(0.004, Math.min(6.0, pOutMPa));
        double sIn = tb.getSteamEntropyByTemperature(tIn);
        double tOut = tb.getSteamTemperatureByPressure(pOut);
        double sf = tb.getWaterEntropyByTemperature(tOut);
        double sfg = tb.getVaporizationEntropyByTemperature(tOut);
        double x = (sIn - sf) / Math.max(1e-6, sfg);
        x = Math.max(0.0, Math.min(1.0, x));
        double hf = tb.getWaterEnthalpyByTemperature(tOut);
        double hfg = tb.getSpecificVaporEnthalpyByTemperature(tOut);
        double hIn = tb.getSteamEnthalpyByTemperature(tIn);
        return Math.max(0.0, hIn - (hf + x * hfg)) * ISENTROPIC_EFFICIENCY;
    }

    /** 并网后的电功率（MW）：bench 与运行时同一套公式 */
    static double generatorLoadMW(double flowKgs, double tInC, double pCondMPa) {
        return (flowKgs - NO_LOAD_STEAM_FLOW_KGS) * specificWorkKjPerKg(tInC, pCondMPa)
                * GENERATOR_EFFICIENCY / 1000.0;
    }

    /**
     * 未并网时的目标转速（rpm）。
     *
     * 2026-10-17 修复"无汽自转"：原式 3000*(1+0.15*(loadRatio-1)) 在 loadRatio≤0 时
     * 仍给出约 2540 rpm 的正目标 —— 主汽门全关、完全没有蒸汽时汽轮机也会自己转到
     * 2540 rpm（玩家看到的"汽轮机会自转"）。
     *
     * 现在分两段：
     *   1) 进汽不超过空载汽耗（20 kg/s）→ 0：没有净驱动蒸汽就不该转；
     *   2) 升速段：0~35% 汽量线性加速，35%（≈328 kg/s）到额定 3000 rpm，
     *      玩家把主汽门开到三分之一即可并网；
     *   3) 调速段：35%~100% 维持 3000；超过额定汽量才按 15% 调差上升，
     *      到 1.6 倍额定（≈3250 rpm）触发超速跳闸。
     */
    static double speedBenchmark(double flowKgs) {
        double loadRatio = (flowKgs - NO_LOAD_STEAM_FLOW_KGS)
                / (RATED_STEAM_FLOW_KGS - NO_LOAD_STEAM_FLOW_KGS);
        if (loadRatio <= 0.0) {
            return 0.0;
        }
        double spinUp = 3000.0 * Math.min(1.0, loadRatio / 0.35);
        if (loadRatio > 1.0) {
            spinUp *= 1.0 + 0.15 * (loadRatio - 1.0);
        }
        return spinUp;
    }

    public TG() {
        this.condensers = new Condenser[0];
        this.condenser = null;
    }

    public TG(Condenser[] condensers) {
        this.condensers = condensers;
        this.condenser = new CondenserBank(condensers);
    }

    protected static void updateGridPhase() {
        gridPhase += 900.0f;
        gridPhase %= 360.0f;
    }
    
    protected void sync() {
        if (synced) {
            return;
        }
        // 转速窗口按 ±10 rpm（0.33%）判同期：本模型的汽门就是调速器，靠手动节流把转速
        // 精确停在 3000±2.5 里不现实（旧值相当于要求阀门开度精确到 0.08%）
        synced = genAlignment > 48.5 && genAlignment < 51.5 && rpm >= 2990 && rpm <= 3010;
        if (synced) {
            if (isTG1) {
                autoControl.recordEvent(Localization.t("TG-1 Sync"));
            } else {
                autoControl.recordEvent(Localization.t("TG-2 Sync"));
            }
        }
    }
    
    protected void deSync() {
        synced = false;
    }
    
    protected void trip(String reason) {
        if (tripped) {
            return;
        }
        if (isTG1) {
            autoControl.recordEvent(Localization.t("TG-1 Trip: ") + reason);
        } else {
            autoControl.recordEvent(Localization.t("TG-2 Trip: ") + reason);
        }
        synced = false;
        TG1InletValves.forEach(valve -> {
            if (valve.drain.equals(this)) {
                valve.setPosition(0);
                valve.setState(0);
            }
        });
        TG2InletValves.forEach(valve -> {
            if (valve.drain.equals(this)) {
                valve.setPosition(0);
                valve.setState(0);
            }
        });
        if (!this.isTripped() && (tg1.isTripped() || tg2.isTripped()) && core.getThermalPower() > 2400
                && autoControl.azAutoEnabled) {
            autoControl.az1Control.trip("Turbine Trip");
        }
        tripped = true;
    }
    
    /**
     * 无条件解除跳闸（仅供"装载标准初始工况"使用，2026-11-03）。
     *
     * <p>{@link #reset()} 要求凝汽器压力 < 0.030 MPa，而凝汽器压力是 update() 里
     * 按汽空间算出来的 —— 标准工况在同一个调用里刚抽完真空，压力还没重算，
     * reset() 会判"没有真空"而失败，界面就一直显示汽机跳闸。</p>
     */
    void forceReset() {
        tripped = false;
        protection.reset();
    }

    protected void reset() {
        if (rpm < 3250 && condenser.getPressure() < 0.030 && mcc.drum1.getPressure() > 5 && mcc.drum2.getPressure() > 5) {
            tripped = false;
            protection.reset();
            if (isTG1) {
                autoControl.recordEvent(Localization.t("TG-1 Reset"));
            } else {
                autoControl.recordEvent(Localization.t("TG-2 Reset"));
            }
        }
    }

    public void update() {
        genPhase += rpm / 1200.0f * 360.0f;
        genPhase %= 360.0f;
        genAlignment = (genPhase - gridPhase);
        genAlignment = genAlignment < 180 ? genAlignment + 180 : genAlignment - 180;
        genAlignment = genAlignment / 360 * 100; 

        /* 本 tick 各抽汽口抽走的汽（kg/tick，由抽汽口在阀门更新时累加）。
         * 两级合计不能超过总进汽：抽汽再大也不能把汽轮机抽干。 */
        double extDea = extractionPort.takeTick();
        double extLp = lpExtractionPort.takeTick();
        double extTick = extDea + extLp;
        if (extTick > steamInflow && extTick > 1e-9) {
            double k = steamInflow / extTick;
            extDea *= k;
            extLp *= k;
            extTick = steamInflow;
        }
        double tIn = steamInflowTemperature >= 50.0 ? steamInflowTemperature : 160.0;

        if (synced) {
            rpm = 3000;
            genPhase = gridPhase;
            /* 电功率 = 做功到凝汽器的那部分 + 各抽汽段已经做出的功。
             * 抽汽汽在膨胀到该抽汽口压力的过程中做了功，之后不再做功；
             * 直接按 (进汽−抽汽) 算会丢掉这部分功，按全流量算又会凭空多出功。 */
            double flowKgs = Math.max(0.0, steamInflow * 20.0 - extTick * 20.0);
            double wFull = specificWorkKjPerKg(tIn, condenser.getPressure());
            load = (flowKgs - NO_LOAD_STEAM_FLOW_KGS) * wFull * GENERATOR_EFFICIENCY / 1000.0
                 + extDea * 20.0 * specificWorkKjPerKg(tIn, extractionPort.pressureMPa)
                        * GENERATOR_EFFICIENCY / 1000.0
                 + extLp * 20.0 * specificWorkKjPerKg(tIn, lpExtractionPort.pressureMPa)
                        * GENERATOR_EFFICIENCY / 1000.0;
        } else {
            load = 0;
            // 未并网时转速跟随进汽量，带 15% 调速器调差（速度变动率）：
            // 额定汽量 → 3000 rpm，汽量 ±10% 时转速约 ±1.5%，满开阀即可并网。
            double benchmark = speedBenchmark(steamInflow * 20.0);
            if (rpm < benchmark) {
                // 升速时间常数 7.5 s（原 50 s：要 5 分钟才能进同期窗口，实际不可玩）
                rpm += (benchmark - rpm) / 150;
            } else {
                rpm += (benchmark - rpm) / 3000;   // 惰走约 150 s
            }
            if (rpm < 0) {
                rpm = 0;
            }
        }
        /* ---- 润滑油系统（保护要读最新的母管油压，必须先于保护评估）---- */
        updateLubeOil(0.05);
        /* ---- 保护（2026-11-03 全部改由 TurbineProtection 统一评估）----
         * 原来的判据散在这里：超速 3250、真空 0.030、汽包 5 MPa、过负荷 825、
         * 逆功率（另开线程 sleep 7 s）。现在集中到 TurbineProtection，
         * 并按真实机组补齐了"机械超速脱扣 111.5%"，且面板可以投退电气保护。 */
        protection.evaluate(this, 0.05);
        reversePower = load < 0.0 && isSynced() && !isTripped();
        lastStepSteamInflow = steamInflow;
        steamOutflow = Math.max(0.0, steamInflow - extTick);   // 抽汽之后的余汽进凝汽器
        /* 排汽温度 = 凝汽器压力下的饱和温度（不是"进汽温度 × 0.92"这种拍脑袋系数）。
         * 0.7 MPa 进汽 ×0.92 会给出 152°C 的"排汽"，而凝汽器实际在 36°C 凝结，
         * 两者差 116 K，凝汽器的热井/真空会按错误的排汽焓记账。 */
        steamOutflowTemperature = (condenser != null)
                ? condenser.getSteamTemperature() : steamInflowTemperature;
        condenser.updateSteamInflow(steamOutflow, steamOutflowTemperature);
        resetFlows();
    }

    @Override
    public double getSteamDensity() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSteamDensity'");
    }

    @Override
    public double getWaterDensity() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getWaterDensity'");
    }

    @Override
    public double getSteamMass() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSteamMass'");
    }

    @Override
    public double getSteamVolume() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSteamVolume'");
    }

    @Override
    public double getSteamTemperature() {
        return steamInflowTemperature;
    }
    
    @Override
    public void updateSteamOutflow(double flow, double tempC) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateSteamOutFlow'");
    }

    @Override
    public void updateSteamInflow(double flow, double tempC) {
        Double[] flowData = NPPMath.mixSteam(steamInflow, steamInflowTemperature, flow, tempC);
        steamInflow = flowData[0];
        steamInflowTemperature = flowData[1];
    }

    @Override
    public void updateWaterOutflow(double flow, double tempC) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateWaterOutFlow'");
    }

    @Override
    public void updateWaterInflow(double flow, double tempC) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updateWaterInFlow'");
    }
    
    public float getRpm() {
        return rpm;
    }
    
    public float getGenAligmnent() {
        return genAlignment;
    }
    
    public boolean isTripped() {
        return tripped;
    }

    /** 是否已并网（保护里"低主汽压力/逆功率"只在并网后判） */
    public boolean isSynced() {
        return synced;
    }

    /** 本机保护对象（汽轮机保护面板读写它） */
    TurbineProtection getProtection() {
        return protection;
    }
    
    public double getSteamInflow() {
        return lastStepSteamInflow;
    }
    
    public double getGeneratorLoad() {
        return load;
    }

    public void forceSync() {
        rpm = 3000;
        synced = true;
    }
    
    public boolean isReversePower() {
        return reversePower;
    }

    // ================================================================
    // 润滑油系统（A = 交流主油泵 / B = 直流事故油泵）
    // ================================================================

    /** 每帧推进润滑油系统：泵组 → 母管油压 → 油温 */
    private void updateLubeOil(double dt) {
        if (!lubeInitialised) {
            // 旧存档没有这几个字段：按"主泵已投、油压已建立"补齐
            lubeInitialised = true;
            lubePumpA = true;
            lubePumpB = false;
            lubePress = LUBE_RATED_BAR;
        }
        ElectricalGrid grid = NPPSim.grid;
        boolean acOk = grid == null || grid.has380();     // 主油泵接厂用 380 V
        boolean dcOk = grid == null || grid.hasDC();      // 事故油泵接 220 V 直流
        lubePumpARunning = lubePumpA && acOk;
        lubePumpBRunning = lubePumpB && dcOk;

        /* ---- B 泵联锁：母管油压低于 1.6 bar（主泵停运或失去交流）自动启 ---- */
        if (lubeStandbyAuto && dcOk && !lubePumpBRunning && lubePress < LUBE_STANDBY_BAR) {
            lubePumpB = true;
            lubePumpBRunning = true;
            if (!lubeStandbyAnnounced) {
                lubeStandbyAnnounced = true;
                event(tag() + " standby lube oil pump B auto-start - low oil pressure");
            }
        } else if (lubePress >= LUBE_STANDBY_BAR + 0.4) {
            lubeStandbyAnnounced = false;
        }

        int pumps = (lubePumpARunning ? 1 : 0) + (lubePumpBRunning ? 1 : 0);
        /* 主轴泵：额定转速附近才有效，最多补 0.5 bar —— 够惰走，替代不了 A/B 泵。
         * 所以满负荷下把两台泵都停掉，3 s 后照样因低油压跳机。 */
        double shaft = rpm > 1500 ? Math.min(1.0, (rpm - 1500) / 1500.0) * 0.5 : 0.0;
        double target = pumps == 0 ? shaft
                : LUBE_RATED_BAR + 0.35 * (pumps - 1) + shaft;
        // 起泵升压 2.0 bar/s；失压 0.45 bar/s（2.6 → 0.8 约 4 s，正好越过 3 s 保护延时）
        lubePress = stepTo(lubePress, target, dt, pumps > 0 ? 2.0 : 0.45);

        /* 油温：空载 42 °C、满负荷 58 °C，τ≈150 s */
        double loadRatio = Math.min(1.0, Math.max(0.0, load / 1000.0));
        lubeOilTemp = thermalStep(lubeOilTemp, 42.0 + 16.0 * loadRatio, dt, 150.0);
    }

    private String tag() {
        return isTG1 ? "TG-1" : "TG-2";
    }

    private void event(String text) {
        if (autoControl != null) {
            autoControl.recordEvent(text);
        }
    }

    private static double stepTo(double cur, double target, double dt, double ratePerSec) {
        double d = target - cur;
        double max = ratePerSec * dt;
        return Math.abs(d) <= max ? target : cur + Math.signum(d) * max;
    }

    private static double thermalStep(double cur, double target, double dt, double tau) {
        return cur + (target - cur) * (1.0 - Math.exp(-dt / Math.max(1e-6, tau)));
    }

    /** 母管油压 bar（保护与仪表共用） */
    double getLubePress() {
        return lubePress;
    }

    /** 润滑油温 °C */
    double getLubeOilTemp() {
        return lubeOilTemp;
    }

    /** 主油泵 A 开关位置 */
    boolean isLubePumpA() {
        return lubePumpA;
    }

    /** 事故油泵 B 开关位置 */
    boolean isLubePumpB() {
        return lubePumpB;
    }

    /** A 泵实际在转（开关合上且交流有电） */
    boolean isLubePumpARunning() {
        return lubePumpARunning;
    }

    /** B 泵实际在转（开关合上且直流有电） */
    boolean isLubePumpBRunning() {
        return lubePumpBRunning;
    }

    /** 本机正在供油的泵台数 */
    int getLubePumpCount() {
        return (lubePumpARunning ? 1 : 0) + (lubePumpBRunning ? 1 : 0);
    }

    /** B 泵低油压自启联锁是否投入 */
    boolean isLubeStandbyAuto() {
        return lubeStandbyAuto;
    }

    void setLubePumpA(boolean on) {
        if (lubePumpA == on) {
            return;
        }
        lubePumpA = on;
        event(tag() + " lube oil pump A " + (on ? "started" : "stopped"));
    }

    void setLubePumpB(boolean on) {
        if (lubePumpB == on) {
            return;
        }
        lubePumpB = on;
        event(tag() + " lube oil pump B " + (on ? "started" : "stopped"));
    }

    void setLubeStandbyAuto(boolean on) {
        if (lubeStandbyAuto == on) {
            return;
        }
        lubeStandbyAuto = on;
        event(tag() + " lube oil pump B auto-start " + (on ? "enabled" : "disabled"));
    }

    /** 除氧器抽汽口：汽源是汽轮机中压缸（不是主蒸汽管）。
     *  阀门按 ΔP 从 0.60 MPa 抽汽，抽汽量记入 TG，由 TG 在功率里折算做功。 */
    class ExtractionPort implements Connectable, SteamSupplyLimit {
        /** 抽汽口压力（MPa，固定） */
        final double pressureMPa;
        /** 该级最多可抽走总进汽的比例 */
        private final double maxFraction;
        private double kgTick = 0.0;
        private double rateKgs = 0.0;

        ExtractionPort(double pressureMPa, double maxFraction) {
            this.pressureMPa = pressureMPa;
            this.maxFraction = maxFraction;
        }

        /** 取出本 tick 的抽汽量（kg）并清零；同时记录 kg/s 供界面读 */
        double takeTick() {
            double v = kgTick;
            kgTick = 0.0;
            rateKgs = v * 20.0;
            return v;
        }

        double getRateKgs() { return rateKgs; }

        @Override public double getPressure() {
            return pressureMPa;
        }
        @Override public double getSteamDensity() {
            return NPPSim.tables.getSteamDensityByPressure(pressureMPa);
        }
        @Override public double getWaterDensity() { return getSteamDensity(); }
        @Override public double getSteamMass() { return 0.0; }
        @Override public double getWaterMass() { return 0.0; }
        @Override public double getSteamVolume() { return 0.0; }
        @Override public double getWaterTemperature() { return getSteamTemperature(); }
        @Override public double getSteamTemperature() {
            return NPPSim.tables.getSteamTemperatureByPressure(pressureMPa);
        }
        @Override public double getWaterLevel() { return 0.0; }
        @Override public void updateSteamOutflow(double flow, double tempC) {
            kgTick += Math.max(0.0, flow);
        }
        @Override public void updateSteamInflow(double flow, double tempC) { }
        @Override public void updateWaterOutflow(double flow, double tempC) { }
        @Override public void updateWaterInflow(double flow, double tempC) { }
        /** 本 tick 还能抽多少（kg）：不超过总进汽的 maxFraction */
        @Override public double availableSteamKg() {
            return Math.max(0.0, steamInflow * maxFraction - kgTick);
        }
    }

    private void reversePowerTrip() {
        if(!reversePowerRecorded) {
            if (isTG1) {
                autoControl.recordEvent(Localization.t("TG-1 Reverse Power"));
            } else {
                autoControl.recordEvent(Localization.t("TG-2 Reverse Power"));
            }
            reversePowerRecorded = true;
        }
        new Thread(() -> {
            try {
                Thread.sleep(7000); // if reverse power for 7 seconds trip turbine;
                if (reversePower) {
                    trip("Reverse Power");
                }
                reversePowerRecorded = false;
            } catch (InterruptedException e) {
                reversePowerRecorded = false;
            }
        }).start();
    }
    
    public void setTG1() {
        isTG1 = true;
    }

    @Override
    public double getWaterMass() {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
}

class Condenser extends WaterSteamSubComponent implements Connectable, UIReadable, Serializable, SteamSupplyLimit, WaterSupplyLimit {
    /** 冷却水出口温度上限 °C（冷却水侧不可能更热；见 update() 里的说明） */
    private static final double CW_OUT_MAX_C = 120.0;
    protected final Pump condenserPump;
    /** 本台循环水额定流量（m³/s）；4 台并联时每台分 1/4 */
    final double cwFlowM3s;
    private final double volume;
    private double feedwaterOutflow = 0.0, feedwaterOutflowRate = 0.0; // kg kg/s
    private double feedwaterMass = 700000, feedwaterTemperature = 20, feedwaterLevel, feedwaterVolume, nominalFeedwaterVolume;
    private double specificDensityFeedwater = Loader.tables.getWaterDensityByTemp(feedwaterTemperature);

    private double volumeWaterSide = 116;
    private double waterMass = 0.0; 
    private double steamMass = 0.0; //initialSteamMass = mass equivalent of non-steam gasses
    double initialSteamMass;

    /** 凝汽器排水阀（热井水位过高时自动排水到除盐水箱）；在 NPPSim 里与水箱连接后才可用 */
    protected WaterValve drainValve;
    /** 应急排水目标（除盐水箱）：水位超过 150% 时直接把多余的水转过去，防止数值溢出 */
    private transient Connectable reliefTarget;
    /** 高水位报警定值（%，相对额定凝水量的偏离量）。
     *  2026-10-18：原 20%/12% 对应约 440 t，二回路改造后额定排汽 ~1800 kg/s、
     *  凝结水 ~1800 kg/s，热井需要更大缓冲，提高到 60%/45%（≈1100 t）。 */
    public static final double HIGH_LEVEL_ALARM = 45.0;
    public static final double HIGH_LEVEL_CLEAR = 30.0;

    // calculated results from the update thread stored here for use in other functions:
    private double steamVolume;
    private double deltaEnergy = 0.0, deltaSteamEnergy = 0.0;
    private double condensationRate = 0.0; //per tick, multiply by 20 for rate/s 
    private double steamInflowTemperature = 20.0; // c
    private double steamDensity;

    /* ---- 不可凝气体（空气）----
     * 原版 jar 的凝汽器就是这么起步的：汽空间按密度 1.647 kg/m³ 折成"空气当量"，
     * 压力 = getSteamPressureByDensity(...) ≈ 0.103 MPa —— 也就是**开机时根本没有真空**，
     * 要靠抽气器/真空泵把空气抽走才建立真空。原版的两处旁证：
     *   · CondensateUI 低真空报警阈值 23 kPa；真空表 0~150 kPa、红区从 23 kPa 起；
     *   · TG 低真空跳闸带 "rpm > 300" 前提（停机时常压不跳闸）。
     * 现在的做法更直接：空气按理想气体算分压，总压 = 饱和压力 + 空气分压（道尔顿定律），
     * 于是"冷态常压 → 抽真空 → 建立真空"这条过程在界面上是看得见的。 */
    private double gasMassKg = 0.0;
    /** 空气漏入率 kg/s（每台凝汽器）：真空系统必须持续把它带走，否则真空会慢慢掉 */
    static final double AIR_LEAK_KGS = 0.004;
    /** 空气气体常数 J/(kg·K) */
    private static final double AIR_R = 287.05;
    /** 20 °C、1 atm 干空气密度 kg/m³（初始化"汽空间充满空气"用） */
    private static final double AIR_DENSITY_20C = 1.204;

    public Condenser() {
        this(45.0);
    }

    public Condenser(double cwFlowM3s) {
        this.cwFlowM3s = cwFlowM3s;
        // 冷却水泵额定 5.14 m³/s（原模型定值）。
        // 注意：按 1850 MW 排热、20C 河水、温升 10 K 计算，物理上需要约 44 m³/s；
        // 5.14 m³/s 在额定排汽下会把凝汽器推到 0.137 MPa/108C（失去真空）。
        // 但直接放大到 45 m³/s 会让现有"汽空间存量 + 经验换热式"数值发散
        // （实测 0.83 MPa → 0），必须先重写凝汽器模型再定径，见审查报告第 12 节。
        /* 热井与冷却水侧的容量按循环水量同比缩放：4 台并联时每台只占 1/4，
         * 否则全厂热井存水会变成原来的 4 倍（质量账会整体偏大。）。 */
        double scale = Math.max(0.05, cwFlowM3s / 45.0);
        feedwaterMass = 700000.0 * scale;
        volumeWaterSide = 116.0 * scale;
        condenserPump = new Pump(750.0f, (float) cwFlowM3s, 5.0, 0, 40, 55, 8000, atmosphere, atmosphere);
        // 凝结水泵：单台凝汽器额定凝结水 ~900 kg/s，原额定 0.417 m³/s（417 kg/s）
        // 不够，热井会持续上涨。改为 0.95 m³/s 并在 NPPSim 里默认 100% 挡位。
        feedwaterVolume = feedwaterMass * specificDensityFeedwater;
        nominalFeedwaterVolume = feedwaterVolume;
        volume = feedwaterVolume * 2;
        steamVolume = volume - feedwaterVolume;
        steamMass = 0;
        initialSteamMass = steamVolume / 1.64718; // constant density for 20c
        waterMass = volumeWaterSide / Loader.tables.getWaterDensityByTemp(condenserPump.source.getWaterTemperature());
        steamDensity = steamVolume / steamMass;
        // 冷态：汽空间充满空气（1 atm），所以开机时没有真空
        gasMassKg = steamVolume * AIR_DENSITY_20C;
    }

    /** 汽空间里的空气量 kg（真空系统的被抽对象；界面上"抽气"读数就是它） */
    public double getGasMassKg() {
        return gasMassKg;
    }

    /**
     * 热井还能被凝结水泵抽走多少 kg（本 tick 的硬上限，2026-11-04）。
     *
     * <p>保底水位 −30 %：凝泵再怎么开也不能把热井抽到这一水位以下。
     * 这是防数值发散的兜底（正常运行时热井水位由 1A 泵出口阀控制，
     * 根本到不了这里）。</p>
     */
    @Override
    public double availableWaterKg() {
        double floorLevel = -30.0;
        double floorMass = nominalFeedwaterVolume * (1 + floorLevel / 100.0)
                / Math.max(1e-6, specificDensityFeedwater);
        return Math.max(0.0, feedwaterMass - floorMass);
    }

    /** 热井存水 kg（区别于 getWaterMass：那是冷却水侧） */
    public double getHotwellMassKg() {
        return feedwaterMass;
    }

    /** 抽走空气（真空泵/抽气器），返回实际抽走的量 kg */
    double removeGas(double kg) {
        double take = Math.min(Math.max(0.0, kg), gasMassKg);
        gasMassKg -= take;
        return take;
    }

    /** 漏入空气（真空破坏阀/正常泄漏用） */
    void addGas(double kg) {
        gasMassKg = Math.max(0.0, gasMassKg + kg);
    }

    /** 汽侧存量：SDV-C 旁路排汽进来时不能超过这里 */
    @Override public double availableSteamKg() {
        return Math.max(0.0, steamMass);
    }

    /**
     * 凝汽器更新（2026-10-18 重写）。
     *
     * 旧模型是"汽空间存量 + 密度反查压力 + 一条经验换热式"，在额定排汽（900 kg/s/台）
     * 下会从 0.10 MPa 一路发散到 0.84 MPa，然后密度查表返回 NaN（实测 360~420 s）。
     * 根因是汽空间只有 ~367 m³、0.006 MPa 下只装得下十几公斤汽，却要吞 45 kg/tick，
     * 而换热强度又不是由热平衡决定，只能靠经验式硬拉，必然失稳。
     *
     * 现在改成稳态集总模型（凝汽器是定压相变器，不需要大汽空间缓冲）：
     *   Q      = m_steam · (h_g − h_f)          —— 排汽凝结放热
     *   ΔT_cw  = Q / (m_cw · cp)                 —— 冷却水温升
     *   T_cond = T_cw,out + 端差(3 K)            —— 凝结温度
     *   p      = p_sat(T_cond)                   —— 真空由凝结温度决定
     * 热井只做质量平衡：凝结水 = 进汽，给水泵抽走多少就少多少。
     * 冷却水中断时 T_cond 上升、真空破坏，出力下降 —— 这是正确响应，不再发散。
     */
    public void update() {
        condenserPump.update();

        // ---- 冷却水：定流量、河水入口温度 ----
        // 循环水泵没有下游母管，基类 Pump 的 timestepFlow 永远为 0（实测 0 kg/s），
        // 这里直接按转速/阀位折算体积流量：45 m³/s × (rpm/额定) × 阀位 × 1000 kg/m³。
        double mCw = Math.max(1.0, cwFlowM3s * 1000.0
                * (condenserPump.getRPM() / 750.0)
                * condenserPump.dischargeValve.getPosition());            // kg/s
        double tCwIn = condenserPump.source.getWaterTemperature();

        // ---- 汽侧：进来的汽全部凝结 ----
        double steamInKgs = Math.max(0.0, steamInflow * 20.0);            // kg/s
        double pNow = Math.max(0.0042, Math.min(0.101, pressure));   // 蒸汽表下限 0.0042 MPa（30C）
        double tSat = Loader.tables.getSteamTemperatureByPressure(pNow);
        double hg = Loader.tables.getSteamEnthalpyByTemperature(tSat);
        double hf = Loader.tables.getWaterEnthalpyByTemperature(
                Math.max(20.0, Math.min(tSat - 0.5, feedwaterTemperature)));
        double qMW = steamInKgs * Math.max(0.0, hg - hf) / 1000.0;        // MW

        // ---- 冷却水温升 → 凝结温度 → 真空 ----
        double dTcw = qMW * 1.0e6 / (mCw * 4180.0);   // MW → W
        /* 冷却水出口温度必须有物理上限（2026-11-02 环路自检抓到）：
         * 冷却水泵停机时 mCw 被兜底成 1 kg/s，dTcw = q·1e6/(mCw·cp) 会算出
         * 上万度，这个温度随后被拿去查水密度 —— 蒸汽表越界 → endSimulation()
         * → 整个电厂冻结在那一帧（模态窗是在更新线程里弹的）。
         * 冷却水侧不可能比这更热；"冷却水中断"的物理后果由下面的 tCond 钳位
         * 体现（真空破坏 → 汽机出力下降），不需要靠一个荒谬的温度。 */
        double tCwOut = Math.max(tCwIn, Math.min(CW_OUT_MAX_C, tCwIn + dTcw));
        double tCond = Math.max(tCwIn + 3.0, Math.min(120.0, tCwOut + 3.0));   // 端差 3 K
        steamTemperature = tCond;
        if (Boolean.getBoolean("cdbg")) {
            System.out.printf("[cond] in=%.1fkg/s mCw=%.0f q=%.0fMW dT=%.1f tCwOut=%.1f tCond=%.1f%n",
                    steamInKgs, mCw, qMW, dTcw, tCwOut, tCond);
        }
        // ---- 空气：正常漏入，由真空泵/抽气器抽走（总压 = 饱和压力 + 空气分压） ----
        gasMassKg += AIR_LEAK_KGS / 20.0;
        pressure = Math.max(0.0008, Math.min(0.10142,
                Loader.tables.getSteamPressureByTemp(tCond)
                        + gasMassKg * AIR_R * (tCond + 273.15) / Math.max(1.0, steamVolume) / 1.0e6));
        steamDensity = Loader.tables.getSteamDensityByPressure(pressure);

        // ---- 热井质量平衡 ----
        double condensateKg = steamInKgs * 0.05;                          // kg/tick
        /* 热井质量守恒 + 上限（2026-10-18）：
         *   - 抽走量不能超过实际存量（否则负质量，下一步被钳掉 → 凝结水莫名损失）；
         *   - 上限按额定容积 ×1.6（≈1100 t），留足负荷波动缓冲；
         *   - 抽空后进入明确空态：水位 0、水温回落环境温度、真空失去。 */
        double draw = Math.min(Math.max(0.0, feedwaterOutflow), Math.max(0.0, feedwaterMass));
        feedwaterMass += condensateKg - draw;
        double maxHotwell = nominalFeedwaterVolume / 0.001 * 1.6;
        feedwaterMass = Math.max(0.0, Math.min(maxHotwell, feedwaterMass));
        if (feedwaterMass <= 1.0) {
            feedwaterMass = 0.0;
            feedwaterLevel = 0.0;
            feedwaterTemperature = 20.0;
            // 注意：不再强行把压力写成大气压 —— 热井抽空不等于真空破坏，
            // 压力仍由"饱和压力 + 空气分压"决定（空气没被抽走就仍接近常压）。
            steamMass = 0.0;
            steamTemperature = 20.0;
            condensationRate = 0.0;
            resetFlows();
            waterLevel = 0.0;
            return;
        }
        feedwaterTemperature = tCond;                                     // 凝结水为饱和温度
        condensationRate = condensateKg;

        // ---- 汽空间只保留一个 tick 的存量（供 availableSteamKg / 界面口径） ----
        steamMass = Math.min(500.0, condensateKg);

        // ---- 冷却水侧（界面/守恒口径） ----
        waterTemperature = tCwOut;
        waterMass = volumeWaterSide / Math.max(1e-6, Loader.tables.getWaterDensityByTemp(waterTemperature));
        waterOutflow = 0.0;

        // ---- 凝汽器排水阀：热井水位过高 → 自动排水到除盐水箱（手动指令优先） ----
        if (drainValve != null) {
            if (drainValve.isAutoEnabled()) {
                if (feedwaterLevel > HIGH_LEVEL_ALARM) {
                    drainValve.setAutoState(2);
                } else if (feedwaterLevel < HIGH_LEVEL_CLEAR) {
                    drainValve.setAutoState(0);
                }
            } else {
                drainValve.setAutoState(1);
            }
            drainValve.update();
        }

        /* 数值卫生：热井水量出现 NaN/负数按额定重置；水位 >150% 时把多余的水转到除盐水箱。
         * "给水带汽过多导致崩溃"时，超量的水/汽在这里被重置为液态水并退出主系统，总量不凭空消失。 */
        if (!Double.isFinite(feedwaterMass) || feedwaterMass < 0.0) {
            feedwaterMass = nominalFeedwaterVolume * Loader.tables.getWaterDensityByTemp(20.0);
        }
        double levelNow = (feedwaterMass * Loader.tables.getWaterDensityByTemp(feedwaterTemperature)
                / nominalFeedwaterVolume - 1.0) * 100.0;
        if (levelNow > 150.0) {
            double excessKg = (levelNow - 150.0) / 100.0 * nominalFeedwaterVolume
                    * Loader.tables.getWaterDensityByTemp(feedwaterTemperature);
            feedwaterMass -= excessKg;
            Connectable target = reliefTarget;
            if (target == null && NPPSim.pcs != null) {
                target = NPPSim.pcs.demineralizedWaterTank;
            }
            if (target != null) {
                target.updateWaterInflow(excessKg, feedwaterTemperature);
            }
        }
        specificDensityFeedwater = Loader.tables.getWaterDensityByTemp(feedwaterTemperature);
        feedwaterVolume = feedwaterMass * specificDensityFeedwater;
        feedwaterLevel = (feedwaterVolume / nominalFeedwaterVolume - 1) * 100;
        steamVolume = Math.max(0.0, volume - feedwaterVolume);
        feedwaterOutflowRate = feedwaterOutflow;
        feedwaterOutflow = 0;
        resetFlows();
        waterLevel = feedwaterLevel;
    }

    @Override
    public double getSteamDensity() {
        return steamDensity;
    }
    
    @Override 
    public double getWaterTemperature() {
        return feedwaterTemperature;
    }

    /**
     * 连接凝汽器排水阀：把热井多余的水排到 target（除盐水箱）。
     * 由 NPPSim 在 PCS（含除盐水箱）创建之后调用。
     */
    /** 应急排水目标（除盐水箱）。与排水阀配合：阀跟不上时按水位直接转移多余水量。 */
    public void setReliefTarget(Connectable target) {
        this.reliefTarget = target;
    }

    public void attachDrain(Connectable target) {
        if (drainValve == null) {
            // 热井在真空下，用重力（倒灌）排水阀而不是普通压差水阀
            drainValve = new CondenserDrainValve(this, target, 150.0);
        }
    }

    public WaterValve getDrainValve() {
        return drainValve;
    }

    /** 热井水位是否过高（用于报警） */
    public boolean isWaterLevelHigh() {
        return waterLevel > HIGH_LEVEL_ALARM;
    }

    @Override
    public double getWaterDensity() {
        return specificDensityFeedwater;
    }

    @Override
    public double getSteamMass() {
        return steamMass;
    }

    @Override
    public double getSteamVolume() {
        return steamVolume;
    }

    @Override
    public void updateSteamOutflow(double flow, double tempC) {
        steamOutflow += flow;
    }

    @Override
    public void updateSteamInflow(double flow, double tempC) {
        Double[] flowData = NPPMath.mixSteam(steamInflow, steamInflowTemperature, flow, tempC);
        steamInflow = flowData[0];
        steamInflowTemperature = flowData[1];
    }

    @Override
    public void updateWaterOutflow(double flow, double tempC) {
        feedwaterOutflow += flow;
    }

    @Override
    public void updateWaterInflow(double flow, double tempC) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getWaterInflowRate'");
    }

    @Override
    public double getWaterOutflowRate() {
        return feedwaterOutflowRate * 20;
    }
    
    public double getCondenserWaterTemperature() {
        return waterTemperature;
    }

    /** 热井是否抽空（界面据此显示空态） */
    public boolean isEmpty() {
        return feedwaterMass <= 1.0;
    }
    
    public double getCondensationRate() {
        return condensationRate * 20;
    }

    @Override
    public double getWaterMass() {
        return waterMass;
    }
}


/**
 * 真空抽气泵（电动，凝结水页面控制）：抽出凝汽器内的空气/不可凝气体以维持真空。
 * 硬性下限：凝汽器压力 ≤ {@link #MIN_PRESSURE_MPA} 时抽气量为 0，确保真空不会被抽得过低；
 * 越接近下限抽气越慢（线性衰减），正常运行时空气泄漏会被持续带走。
 */
class VacuumPump implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    final CondenserBank condenser;
    float rpm = 0.0f;
    /**
     * 真空泵运行状态。
     *
     * <p>2026-10-27 按要求改成**默认停机**：冷态开机时凝汽器就是常压（真空度 0%），
     * 需要操作员在"凝结水"窗口的真空泵面板上手动点【开始】才抽真空 ——
     * 这也符合真实机组的启动顺序（先投循环水泵/抽气器，再抽真空）。</p>
     */
    boolean running = false;
    double timestepFlow = 0.0;                       // 每 tick（50ms）抽出的空气 kg
    static final float RATED_RPM = 2985f;
    /** 抽气压力下限（绝压 MPa）：凝汽器压力低于此值时不再抽气 */
    public static final double MIN_PRESSURE_MPA = 0.003;
    /** 满转速、常压下的抽气量（kg/s，空气当量）。
     *  按本机实测帧率（约 6 Hz，见 SystemInitCheck）折算，从 1 atm 抽到工作真空
     *  约 1~1.5 分钟；真实机组靠抽气器/真空泵是 20~40 分钟，这里为可玩性压缩。 */
    static final double RATED_FLOW_KG_PER_S = 30.0;

    VacuumPump(CondenserBank condenser) {
        this.condenser = condenser;
    }

    public void setRunning(boolean r) {
        running = r;
    }

    public boolean isRunning() {
        return running;
    }

    public float getRpm() {
        return rpm;
    }

    /** 满载约 60 A */
    public double getPowerUsage() {
        return 60.0 * (rpm / RATED_RPM);
    }

    /** 抽气流量（kg/s） */
    public double getFlowRate() {
        return timestepFlow * 20;
    }

    public double getTemperature() {
        return condenser.getWaterTemperature();   // 热井水温（启动 20°C；蒸汽侧未建立时为 0）
    }

    void update() {
        float target = running ? RATED_RPM : 0f;
        float step = RATED_RPM * 0.05f;              // 约 1 秒完成启停
        if (rpm < target) {
            rpm = Math.min(target, rpm + step);
        } else if (rpm > target) {
            rpm = Math.max(target, rpm - step);
        }
        double p = condenser.getPressure();
        if (running && rpm > RATED_RPM * 0.05f && p > MIN_PRESSURE_MPA) {
            double taper = Math.min(1.0, (p - MIN_PRESSURE_MPA) / (0.10142 - MIN_PRESSURE_MPA));
            double want = RATED_FLOW_KG_PER_S / 20.0 * (rpm / RATED_RPM) * taper;
            // 抽走的是**空气**（不可凝气体），不是蒸汽：真空就是这么建立起来的
            timestepFlow = condenser.removeGas(want);
        } else {
            timestepFlow = 0.0;
        }
    }
}
