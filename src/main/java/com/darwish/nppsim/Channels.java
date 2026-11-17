package com.darwish.nppsim;

import static com.darwish.nppsim.NPPSim.autoControl;
import java.awt.Color;
import java.io.Serializable;

interface ChannelUIUpdatable {
    void updateTableValues();
}

class ChannelUIData implements Serializable {
    protected String positionString = "00-00";
    protected String name = "Channel";
    protected java.awt.Color UIBackgroundColor = new Color(235, 235, 160), UISelectedColor = Color.WHITE;
    protected java.awt.Color UISelsynColor = new Color(245, 245, 160);
    protected Object tableData[][];
    protected static java.text.DecimalFormat sf = new java.text.DecimalFormat("0.####E0");

    public void setTableData(Object[][] tableData) {
        this.tableData = tableData;
    }

    public void setCoreMapColors(Color background, Color selected) {
        this.UIBackgroundColor = background;
        this.UISelectedColor = selected;
    }
    
    public void setSelsynColors(Color selsyn) {
        this.UISelsynColor = selsyn;
    }

    public void setName(String name) {
        this.name = name;
    }
}

abstract class Channel implements ChannelUIUpdatable, Serializable {
    protected ChannelUIData uiData = new ChannelUIData();
    private final Double  initialNeutronCount = 1.0;
    private Double fastNeutronCount = 1.0, thermalNeutronCount = 0.0; //thermalNeutronCount is not used for anything yet
    protected double thermalFissionFactor = 1, thermalUtilizationFactor = 1, resonanceEscapeProb = 0.87, fastFissionFactor = 1, fastNonLeakageProb = 0.97, thermalNonLeakageProb = 0.99;

    public void update() {
        
    }
    
    public void updateNeutronPopulation() {
        fastNeutronCount *= thermalFissionFactor * fastNonLeakageProb * resonanceEscapeProb * thermalNonLeakageProb * thermalUtilizationFactor;
    }

    public void resetFlowRates() {
    };

    public ChannelUIData getUIData() {
        return uiData;
    }
    
    public void setPositionString(String positionString) {
        this.uiData.positionString = positionString;
    }

    /**
     * 
     * @return array of {fastNeutronCount, thermalNeutronCount}
     */
    public Double[] getNeutronPopulation() {
        return new Double[] {fastNeutronCount, thermalNeutronCount};
    }

    /**
     * 性能优化：热路径专用的免装箱访问方法。
     * 原 {@link #getNeutronPopulation()} 每帧为每个通道分配一个新的 Double[]，
     * 在 50x50 的核心网格上以 20Hz 刷新会持续产生垃圾、加重 GC。
     * @return 快中子数
     */
    public double getFastNeutronCount() {
        return fastNeutronCount;
    }

    /**
     * 性能优化：热路径专用的免装箱访问方法。
     * @return 热中子数
     */
    public double getThermalNeutronCount() {
        return thermalNeutronCount;
    }

    /**
     * 性能优化：热路径专用的免装箱写入方法，避免 {@link #setNeutronPopulation(Double[])} 的装箱与数组分配。
     * @param fast 快中子数
     * @param thermal 热中子数
     */
    public void setNeutronPopulation(double fast, double thermal) {
        fastNeutronCount = fast;      // 相对通量口径（额定通道=1.0，停堆源量程 ≪1）——
        thermalNeutronCount = thermal; // 旧 CA 的 1.0 下限钳制已删，否则停堆态 AR/Count 全被钉在 1
    }

    public void setNeutronPopulation(Double[] neutronPopulation) {
        fastNeutronCount = neutronPopulation[0];
        thermalNeutronCount = neutronPopulation[1];
    }
}

abstract class CPSChannel extends Channel {
    protected double waterTemperature = 0.0;
    protected double waterInflowTemperature = 0.0;
    protected double waterInflowRate = 0.0;
    protected double pressure = 0.0;
    /**
     * 该通道所在位置的相对通量形状（中心高、边缘低，均值≈1）。
     *
     * <p>原始 jar 里 CPS 通道的中子数来自 56×56 元胞自动机的邻域扩散，
     * 所以堆芯图上 223 个绿点会随位置明暗变化。改用点堆内核后只给燃料通道
     * 下发通量，CPS 的 fastNeutronCount 永远停在初值 1.0 —— 整层绿点恒暗、
     * 失去意义。这里按与燃料通道同一套径向形状补上。</p>
     */
    private double radialShape = 1.0;

    public void setRadialShape(double shape) {
        this.radialShape = shape;
    }

    public double getRadialShape() {
        return radialShape;
    }

    @Override
    public void updateTableValues() {
        uiData.setTableData(new Object[][] {
                { Localization.t("Type:"), uiData.name, "" },
                { Localization.t("Count:"), ChannelUIData.sf.format(getNeutronPopulation()[0]), "" },
//                { Localization.t("Pressure:"), NPPMath.round(pressure), "Mpa" },
//                { Localization.t("Water Inflow:"), NPPMath.round(waterInflowRate), "kg/s" },
//                { Localization.t("Inlet Temperature:"), NPPMath.round(waterInflowTemperature), "C" },
//                { Localization.t("Outlet Temperature:"), NPPMath.round(waterTemperature), "C" }
        });
    }
}

abstract class ControlRodChannel extends CPSChannel {
    private float position = 1.0f;
    /** 原固定棒速（0.002857/tick ≈ 17.5 s 全行程）；现在由 {@link RodSpeed} 统一给，字段保留以兼容旧存档 */
    protected float speed = 0.0028571429f;
    protected float scramSpeed = 0.0038461538f;
    protected float fastScramSpeed = scramSpeed;
    private int state = 1, autoState = 1;
    private boolean scram = false, fastScram = false;
    
    @Override
    public void updateTableValues() {
        uiData.setTableData(new Object[][] {
                { Localization.t("Type:"), uiData.name, "" },
                { Localization.t("Position: "), NPPMath.round(position * 7), "m" },
//                { Localization.t("Pressure:"), NPPMath.round(pressure), "Mpa" },
//                { Localization.t("Water Inflow:"), NPPMath.round(waterInflowRate), "kg/s" },
//                { Localization.t("Inlet Temperature:"), NPPMath.round(waterInflowTemperature), "C" },
//                { Localization.t("Outlet Temperature:"), NPPMath.round(waterTemperature), "C" }
        });
    }
    
    @Override
    public void update() {
        if (scram) {
            if (fastScram) {
                position = NPPMath.updatePositionFromState(2, 2, position, fastScramSpeed);
            } else {
                position = NPPMath.updatePositionFromState(2, 2, position, scramSpeed);
            }
        } else {
            // 正常驱动：速度取全局"棒速度"档位（最高档 4 s 全行程），紧急停堆仍走上面两条固定速度
            position = NPPMath.updatePositionFromState(state, autoState, position, RodSpeed.stepPerTick());
        }
        thermalUtilizationFactor = 1.0 - position;
    }
    
    public float getPosition() {
        return position;
    }

    public void setPosition(float position) {
        this.position = position;
    }
    
    public void setState(int state) {
        this.state = state;
    }
    
    public void setAutoState(int autoState) {
        this.autoState = autoState;
    }
    
    /** 是否处于紧急插棒状态（AZ 触发后置位；诊断/自检用） */
    public boolean isScram() {
        return scram;
    }

    public void setScram(boolean scram) {
        this.scram = scram;
    }
    
    public void setFastScram(boolean scram) {
        this.fastScram = scram;
    }
}

class FissionChamber extends CPSChannel {
    public FissionChamber() {
        uiData.setName(Localization.t("Fission Chamber"));
    }

}

class PDMSChannel extends CPSChannel {
    public PDMSChannel() {
        uiData.setName(Localization.t("PDMS-A Channel"));
    }
}

class MCRChannel extends ControlRodChannel {
    public MCRChannel() {
        uiData.setCoreMapColors(Color.GRAY.brighter(), Color.GRAY.brighter().brighter());
        uiData.setName(Localization.t("Manual Control Rod"));

    }
}

class ACRChannel extends MCRChannel {
    public ACRChannel() {
        speed = 0.0014285714f;
        uiData.setName(Localization.t("Automatic Control Rod Group 1"));
        uiData.setSelsynColors(Color.GREEN.darker().darker());
        uiData.setCoreMapColors(Color.GREEN.darker().darker(), Color.GREEN.darker().brighter());
    }
}
class LEPChannel extends MCRChannel {
    public LEPChannel() {
        uiData.setName(Localization.t("Local Emergency Protection Rod"));
        uiData.setCoreMapColors(new Color(200, 73, 73), Color.PINK);
    }
}

class MCRMChannel extends ControlRodChannel {
    public MCRMChannel() {
        uiData.setName(Localization.t("Manual Control Rod Modernized"));
        uiData.setCoreMapColors(Color.GRAY.brighter(), Color.GRAY.brighter().brighter());
    }
}

class LACChannel extends MCRMChannel {
    public LACChannel() {
        speed = 0.0014285714f;
        uiData.setName(Localization.t("Local Automatic Control Rod"));
        uiData.setSelsynColors(Color.BLUE);
        uiData.setCoreMapColors(new Color(30, 30, 255), Color.CYAN);
    }
}

class FASRChannel extends ControlRodChannel {
    public FASRChannel() {
        speed = 0.0041071429f;
        scramSpeed = 0.0082142857f;
        fastScramSpeed = 0.02f;
        uiData.setName(Localization.t("Fast-Acting Scram Rod"));
        uiData.setSelsynColors(Color.RED);
        uiData.setCoreMapColors(Color.RED, Color.PINK);
    }
}

class SARChannel extends ControlRodChannel {
    public SARChannel() {
        uiData.setName(Localization.t("Shortened Absorber Rod"));
        uiData.setSelsynColors(Color.ORANGE);
        uiData.setCoreMapColors(Color.ORANGE, Color.ORANGE.brighter());
    }
}

class SACRChannel extends SARChannel {
    public SACRChannel() {
        uiData.setName(Localization.t("Automatic Control Rod Group 2"));
        uiData.setSelsynColors(Color.GREEN.darker());
        uiData.setCoreMapColors(Color.GREEN.darker().darker(), Color.GREEN.darker().brighter());
    }
}

class VoidChannel extends Channel {

    @Override
    public void updateTableValues() {
        // TODO Auto-generated method stub
        // throw new UnsupportedOperationException("Unimplemented method
        // 'upateTableValues'");
    }

}

class ReflectorChannel extends Channel {
    
    public ReflectorChannel() {
        resonanceEscapeProb = 0.95;
    }
    @Override
    public void updateTableValues() {
        // TODO Auto-generated method stub
        // throw new UnsupportedOperationException("Unimplemented method
        // 'upateTableValues'");
    }

}

class ReflectorCoolingChannel extends ReflectorChannel {

    public ReflectorCoolingChannel() {
        super();
    }
    @Override
    public void updateTableValues() {
        // TODO Auto-generated method stub
        // throw new UnsupportedOperationException("Unimplemented method
        // 'upateTableValues'");
    }

}

class FuelChannel extends Channel implements Connectable, UIReadable {

    private Connectable drum;
    /* 通道内冷却剂容积 m³ —— 按实堆几何标定（2026-11-01）。
     * RBMK 压力管：内径 80 mm、堆芯高 7 m，内装 2×18 根 φ13.6 mm 燃料棒：
     *   流通面积 = π·0.04² − 18·π·0.0068² = 2.41e-3 m² → 容积 = 2.41e-3 × 7 = 0.0169 m³
     * 即 284 °C 时约 12.5 kg/通道、全堆约 21 t。
     * 旧值 0.2194 m³（219 kg/通道、全堆 364 t）比实堆大约 13 倍，带来的后果：
     *   - 通道热惯性时间常数 ~30 s（实堆 ~1.7 s），功率变化后产汽严重滞后；
     *   - 一回路（通道+汽包）总装量虚高 45%，冷启动升温慢一倍以上；
     *   - 364 t 水不在 MassLedger 里，全厂水量对不上账。 */
    private final double volume = 0.0169;
    private double waterMass;
    private double waterTemperature = 20.0, steamTemperature = 20.0; // temperature in c
    private double thermalPower = 0.0; // thermal power in MWt
    private double pressure = 0.10142; // pressure in Mpa
    private double steamMass = 0.0; // kg
    private double waterLevel = 0.0; // percentage above or below nominal
    private double thermalLoss = 0.0; // conductive energy loss for the primary circuit Mwt
    private double voidFraction = 0.0; // % of mass steam in coolant loop

    /* ---- 干涸 / 包壳过热模型（2026-11-01） ----
     * 两节点：裂变热 → 金属热容 → hA×(Tclad−Twater) → 冷却剂。
     * 湿份额（当前水装量 / 额定水装量）下降时 hA 按平方衰减，水被打光后金属温度按功率上冲。
     * 这是"低流量 + 高功率"唯一的可见后果——之前热量直接进冷却剂，干涸时凭空消失。 */
    private double cladTempC = 20.0;            // 包壳/压力管等效金属温度
    private double nominalWaterMass = 12.5;     // 284°C 饱和水装满通道时的质量 kg
    private boolean dryout = false;
    private double wetFraction = 1.0;

    /* ---- 燃耗（2026-11-02）----
     * 中子通量图里"绿色棒"要按燃料的消耗程度上色，而模型此前完全没有燃耗量。
     * 这里按通道累计裂变能量，换算成比燃耗 MWd/kgU：
     *   单通道铀装量 = 192 t / 1661 ≈ 115.6 kg（RBMK-1500 堆芯铀装载量）
     *   设计卸料燃耗 ≈ 20 MWd/kgU
     * 满功率（2.89 MW/通道）下每天约 0.6 MWd/kgU，与实堆换料节奏一致。
     * 只有显示用得到，不参与任何反应性反馈。 */
    private static final double URANIUM_PER_CHANNEL_KG = 115.6;
    static final double DESIGN_BURNUP_MWD_PER_KGU = 20.0;
    /** 颜色刻度铺满（浅绿→灰）对应的比燃耗，见 getDepletion01() 的说明 */
    static final double BURNUP_COLOR_FULL_MWD_PER_KGU = 2.0;
    private double burnupMWdPerKgU = 0.0;

    /** 金属热容 J/K：UO2 ~114 kg + Zr(包壳+压力管) ~65 kg，等效 180 kg × 300 J/kgK */
    private static final double CLAD_HEAT_CAPACITY = 5.4e4;
    /** 额定 2.89 MW 下包壳-冷却剂设计温差 20 K → UA = 2.89e6/20 */
    private static final double CLAD_TO_WATER_UA = 1.445e5;
    /** 湿份额 ≥ 此值：正常泡核沸腾，传热不衰减 */
    private static final double WET_FULL = 0.60;
    /** 湿份额低于此值：判定干涸（界面着色/统计口径） */
    private static final double DRYOUT_WET = 0.35;
    /** 完全干涸时的残存换热倍数（只剩蒸汽对流） */
    private static final double H_MIN = 0.03;
    /** 包壳过热保护定值 °C（Zr 包壳 900 °C 以上急剧氧化，保护取 800） */
    private static final double CLAD_TRIP_TEMP = 800.0;
    /** 堆芯图默认底色（与 ChannelUIData 里的初值一致） */
    private static final Color NORMAL_CHANNEL_COLOR = new Color(235, 235, 160);
    /** 干涸通道在堆芯图上的底色 */
    private static final Color DRYOUT_COLOR = new Color(232, 64, 32);

    // calculated results from the update thread stored here for use in other
    // functions:
    private double specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
    private double specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);// NPPMath.calculateSpecificVaporEnthalpy(feedwaterTemperature);
    private double boilingPoint = Loader.tables.getSteamTemperatureByPressure(pressure);
    private double specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
    private double waterVolume;
    private double steamVolume, steamDensity;
    private double waterInflow = 0.0, waterInflowRate = 0.0; // kg kg/s
    private double waterInflowTemperature = 20.0; // C
    private double waterOutflow = 0.0, waterOutflowRate = 0.0; // kg, kg/s
    private double steamOutFlow = 0.0, steamOutflowRate = 0.0; // kg, kg/s
    private double steamProduction = 0.0; // kg/s
    private double averagePower = 0.0; //average power history for simplified decay heat calculation
    private double tElapsed = 0.0; //elapsed time since reaction stopped
    private double xeThermalUtilizationModifier = 0.0;
    private long tPower = 0; //total time for average power
    private double i135Count = 0, xe135Count = 0; //atoms per channel
    private final double nominalFeedWaterVolume;
    private final double resonanceEscapeProbInitial, thermalUtilizationFactorInitial;
    
    private final double I135DECAY_MULTIPLIER = NPPMath.calculateDecayMultiplierPerUpdate(6.57);
    private final double XE135DECAY_MULTIPLIER = NPPMath.calculateDecayMultiplierPerUpdate(9.14);

    public FuelChannel() {
        uiData.setName(Localization.t("Fuel Channel"));
        waterMass = volume / Loader.tables.getWaterDensityByTemp(waterTemperature);
        waterVolume = waterMass * specificDensityWater;
        steamVolume = 0.0;
        nominalFeedWaterVolume = waterVolume;
        steamDensity = 0.0;
        /* 干涸判据的基准：284 °C 饱和水装满通道时的质量（0.0169 m³ / 1.352e-3 ≈ 12.5 kg）。
         * 通道在体积上是"常满"的，所以水装量只可能因为蒸发快于进水而下降。 */
        double sv = (Loader.tables != null) ? Loader.tables.getWaterDensityByTemp(284.0) : 1.352e-3;
        nominalWaterMass = volume / Math.max(1e-5, sv);
        cladTempC = waterTemperature;
        thermalFissionFactor = 1.735;
        resonanceEscapeProbInitial = resonanceEscapeProb;
        thermalUtilizationFactor = 0.75;
        thermalUtilizationFactorInitial = thermalUtilizationFactor;
    }

    @Override
    public void update() {
        /* 产汽计数按 tick 复位（2026-11-02 环路自检）：
         * 原先 steamProduction 只累加、从不复位，getSteamProduction() 又按 kg/s 乘 20，
         * 于是"通道产汽"这个读数是个只增不减的垃圾数字（实测跑到 7,382,210 kg/s）。
         * 复位后 getter 返回的就是本 tick 的产汽速率 kg/s。 */
        steamProduction = 0.0;
        //waterTemperature -= (0.5 * waterTemperature - 10) * 0.0000001; //TODO thermal loss
        /* 数值卫生（2026-11-01）：干涸 / 失流时水装量与温度会被推到物理定义域外，
         * 而 SteamTables 一旦越界就直接 endSimulation()（弹"component damaged beyond repair"、
         * 停掉整个仿真）。必须在进 mixWater 之前把状态夹回定义域：
         *   水温 5~372 °C（饱和水表 1~373 °C）；水装量 > 0；进水质量非负。 */
        if (!Double.isFinite(waterMass) || waterMass <= 1.0e-9) {
            if (SANITIZE_LOG_COUNT < 3) {
                SANITIZE_LOG_COUNT++;
                System.err.println("[FuelChannel] waterMass 异常=" + waterMass
                        + " T=" + waterTemperature + " clad=" + cladTempC
                        + " P=" + thermalPower + " dry=" + dryout + " -> 重置");
            }
            waterMass = Math.max(1.0e-9, 0.05 * nominalWaterMass);
        }
        if (!Double.isFinite(waterTemperature)) {
            waterTemperature = boilingPoint;
        }
        waterTemperature = Math.max(5.0, Math.min(360.0, waterTemperature));
        if (!Double.isFinite(waterInflow) || waterInflow < 0.0) {
            waterInflow = 0.0;
        }
        if (waterInflowTemperature < 5.0 || waterInflowTemperature > 360.0) {
            waterInflowTemperature = Math.max(5.0, Math.min(360.0, waterInflowTemperature));
        }
        double[] waterInflowData = safeMix(waterMass, waterTemperature, waterInflow, waterInflowTemperature);
        waterTemperature = waterInflowData[1];
        waterMass = waterInflowData[0];

        specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
        specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);
        specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
        pressure = drum.getPressure();
        boilingPoint = Loader.tables.getSteamTemperatureByPressure(pressure);

        thermalLoss = 0;

        /* ---- 金属节点（燃料+包壳+压力管）→ 冷却剂 ----
         * 2026-11-01：原来 `thermalPower*50` kJ 直接进冷却剂，等于假设"不管有没有水都能把热全带走"；
         * 通道干涸时这部分能量会凭空消失。现在拆成两节点：
         *   裂变热 qIn → 金属热容 → qOut = UA·hFactor·(Tclad−Twater) → 冷却剂
         * hFactor 随湿份额衰减：水被打光后几乎不传热，金属温度按功率上冲（保护据此动作）。 */
        wetFraction = nominalWaterMass > 0.0
                ? Math.max(0.0, Math.min(1.0, waterMass / nominalWaterMass)) : 1.0;
        boolean wasDry = dryout;
        dryout = wetFraction < DRYOUT_WET;
        if (dryout != wasDry) {
            // 干涸状态变化时只改一次颜色（堆芯图用 Dryout 红，恢复后回默认），避免每帧改 UI 数据
            uiData.UIBackgroundColor = dryout ? DRYOUT_COLOR : NORMAL_CHANNEL_COLOR;
        }
        double hFactor = (wetFraction >= WET_FULL) ? 1.0
                : Math.max(H_MIN, (wetFraction / WET_FULL) * (wetFraction / WET_FULL));
        double qIn = thermalPower * 1.0e6;                                            // W
        double qOut = CLAD_TO_WATER_UA * hFactor * (cladTempC - waterTemperature);     // W
        cladTempC += (qIn - qOut) / CLAD_HEAT_CAPACITY * 0.05;
        if (hFactor >= 1.0 && cladTempC < waterTemperature) {
            cladTempC = waterTemperature;      // 湿态下不允许低于水温（数值护栏）
        }
        if (!Double.isFinite(cladTempC)) {
            cladTempC = waterTemperature;
        }

        /* qOut 是 W：W × 0.05 s = J，再 /1000 换成 kJ 才能和热容、潜热（kJ）同口径。
         * 2026-11-01 初版漏了 /1000，通道实际拿到 1000 倍热量，满流量下也会被瞬间烧干。 */
        double energy = qOut * 5.0e-5 - thermalLoss * 50;    // kJ / tick
        double deltaSteamMass;
        waterTemperature += (energy / (specificHeatWater * waterMass));
        /* 数值护栏：饱和蒸汽表只定义在 1~373 °C，越界会让 SteamTables 直接 endSimulation()。
         * 两节点模型在状态不一致（例如刚 setWaterTemp 完）时会算出很大的负换热，
         * 一帧就能把水温拉到 0 °C 以下 —— 这里把它夹回定义域，异常状态留给保护去处理。 */
        if (!Double.isFinite(waterTemperature)) {
            waterTemperature = boilingPoint;
        }
        waterTemperature = Math.max(5.0, Math.min(360.0, waterTemperature));
        specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
        specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);
        if (waterTemperature > boilingPoint) {
            deltaSteamMass = ((waterTemperature - boilingPoint) * specificHeatWater * waterMass) / specificVaporEnthalpy;
            /* 干锅护栏（2026-11-01）：水不够时不能把水蒸成负数。
             * 热量改走通道以后，"低流量 + 高功率"真的会把通道烧干。没有这道护栏时，
             * deltaSteamMass 只由能量决定（=energy/hfg，与水装量无关），水装量会被扣成负数，
             * 下一拍 mixWater 拿到负质量 → NaN → 一路传到汽包和界面。
             * 护栏只保证不出现非物理值；干锅本身的后果（传热恶化、燃料升温）仍未建模。 */
            if (deltaSteamMass > waterMass * 0.5) {
                deltaSteamMass = waterMass * 0.5;
            }
            waterTemperature = boilingPoint;
            steamTemperature = boilingPoint;

            steamProduction += deltaSteamMass;
            steamMass += deltaSteamMass;
            waterMass -= deltaSteamMass;
        }
        
        specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
        waterVolume = specificDensityWater * waterMass;
        waterOutflow = (waterVolume - volume) / specificDensityWater;
        if (waterOutflow < 0) {
            // TODO
            waterOutflow /= thermalPower == 0 ? 1 : thermalPower * 1000;
        }

        if (waterOutflow < 0) {
            double[] reverseFlowData = safeMix(waterMass, waterTemperature, 0 - waterOutflow,
                    drum.getWaterTemperature());
            waterMass = reverseFlowData[0];
            waterTemperature = reverseFlowData[1];
        } else {
            waterMass -= waterOutflow;
        }

        specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
        waterVolume = specificDensityWater * waterMass;
        double flow = waterOutflow < 0 ? 0 - waterOutflow : waterOutflow;
        voidFraction = steamMass / (flow + steamMass + 0.00000000000001); //crude way of preventing divide by 0
        steamVolume = volume - waterVolume < 0 ? 0 : volume - waterVolume;
        steamDensity = steamVolume / steamMass;
        waterLevel = (waterVolume / nominalFeedWaterVolume - 1) * 100;
        waterInflowRate = waterInflow;
        waterOutflowRate = waterOutflow;
        steamOutflowRate = steamOutFlow;
        steamOutFlow = 0; // reset lostSteam for next timeStep
        waterInflow = 0;

        // 反应堆物理已由 ReactorKinetics（8 节点点堆动力学）统一计算：
        // 本通道功率与碘/氙由引擎每 tick 下发（含衰减热），此处只保留超功率保护
        thermalPower = externalPower;
        i135Count = extI135;
        xe135Count = extXe135;
        // 燃耗累积：P[MW] × dt[天] / 铀装量[kgU] → MWd/kgU（tick = 50 ms）
        if (thermalPower > 0.0 && Double.isFinite(thermalPower)) {
            burnupMWdPerKgU += thermalPower * (0.05 / 86400.0) / URANIUM_PER_CHANNEL_KG;
        }
        // 只在"自动 AZ"投入时才允许自动跳闸（关闭后仅保留面板手动 AZ 按钮）
        if (autoControl != null && thermalPower > 5 && autoControl.azAutoEnabled) {
            autoControl.az1Control.trip("High Fuel Channel Power");
        }
        /* 包壳过热保护：干涸后金属温度上冲，800 °C 触发 AZ-1（紧急保护，插全部棒）。
         * 真实 RBMK 没有燃料/包壳温度测点，这里把"等效包壳温度"作为模拟器的保护信号——
         * 否则干涸在界面上只剩一个不痛不痒的红色格子。 */
        if (autoControl != null && cladTempC > CLAD_TRIP_TEMP && autoControl.azAutoEnabled) {
            autoControl.az1Control.trip(Localization.t("High Fuel Channel Temperature"));
        }
    }

    /** 是否处于干涸状态（堆芯图着色 / 干涸通道占比统计） */
    public boolean isDryout() {
        return dryout;
    }

    /** 数值卫生触发次数（诊断用；>0 说明通道状态曾被推到定义域外） */
    private static int SANITIZE_LOG_COUNT = 0;

    /**
     * 带定义域保护的混合。这里**故意不走蒸汽表**：NPPMath.mixWater 会把混合焓反查成温度，
     * 而饱和水表只覆盖约 5~365 °C（62.9~2048.3 kJ/kg），输入一旦越界（干涸时水装量趋零、
     * 温度被推到 372 °C）就会触发 SteamTables.endSimulation()，整个仿真直接停摆。
     * 通道这两个混合点改用质量加权温度混合：cp 在此区间变化约 10%，对集总模型完全够用，
     * 而且结果恒在两端温度之间，不可能越界。
     */
    private static double[] safeMix(double m1, double t1, double m2, double t2) {
        if (!Double.isFinite(m1) || m1 < 0.0) {
            m1 = 0.0;
        }
        if (!Double.isFinite(m2) || m2 < 0.0) {
            m2 = 0.0;
        }
        m1 = Math.min(m1, 1.0e6);
        m2 = Math.min(m2, 1.0e6);
        if (!Double.isFinite(t1)) {
            t1 = 20.0;
        }
        if (!Double.isFinite(t2)) {
            t2 = 20.0;
        }
        t1 = Math.max(5.0, Math.min(360.0, t1));
        t2 = Math.max(5.0, Math.min(360.0, t2));
        double mt = m1 + m2;
        if (mt <= 1.0e-12) {
            return new double[] {0.0, t1};
        }
        double tm = (m1 * t1 + m2 * t2) / mt;
        if (!Double.isFinite(tm)) {
            tm = t1;
        }
        return new double[] {mt, Math.max(5.0, Math.min(360.0, tm))};
    }

    /** 包壳（含压力管）等效金属温度 °C */
    public double getCladTempC() {
        return cladTempC;
    }

    /** 湿份额 0..1：当前水装量 / 284 °C 饱和水装满通道的质量 */
    public double getWetFraction() {
        return wetFraction;
    }

    /** 累计比燃耗 MWd/kgU（只用于中子通量图的燃料消耗着色） */
    public double getBurnupMWdPerKgU() {
        return burnupMWdPerKgU;
    }

    /**
     * 燃料消耗程度 0..1（0 = 新燃料，1 = 颜色走到全灰）。
     *
     * <p>物理上按设计卸料燃耗 {@link #DESIGN_BURNUP_MWD_PER_KGU}（20 MWd/kgU，约 800 天满功率）
     * 折算，但**颜色刻度**只铺在前 {@link #BURNUP_COLOR_FULL_MWD_PER_KGU} 上：
     * 实堆燃耗是按天涨的，按 20 MWd/kgU 铺满颜色的话玩一整天都看不出变化；
     * 铺在前 2 MWd/kgU（约 80 天满功率）并用开方压缩早期段，长局里才能看到
     * "中心区先变灰、边缘区还绿"的趋势。</p>
     */
    public double getDepletion01() {
        double b = burnupMWdPerKgU / BURNUP_COLOR_FULL_MWD_PER_KGU;
        return Math.sqrt(Math.max(0.0, Math.min(1.0, b)));
    }

    /** 本通道相对额定功率的发热水平 0..1（"正在参与反应"的程度） */
    public double getActivation01() {
        // 用引擎本 tick 下发的 externalPower，而不是 update() 里缓存的 thermalPower：
        // 两者差一个 tick，但更重要的是无界面自检/外部调用只跑 Core.update() 时
        // thermalPower 还没同步，会全read成 0（堆芯图整片深绿）。
        return Math.max(0.0, Math.min(1.0, externalPower / 2.8898254064));
    }

    private double externalPower = 0.0;   // ReactorKinetics 下发的功率 MWt
    private double extI135 = 0.0, extXe135 = 0.0;   // 引擎下发的碘/氙状态（原子/通道）
    private double radialShape = 1.0;     // 径向功率形状（Core 初始化，均值=1）

    /** 物理引擎每 tick 下发的功率与 I/Xe 状态（径向形状在 Core 侧折算） */
    public void setKineticsPower(double mw, double i135Atoms, double xe135Atoms) {
        externalPower = mw;
        extI135 = i135Atoms;
        extXe135 = xe135Atoms;
    }

    // ---- 诊断访问器（审计/测试用）----
    double getExternalPower() { return externalPower; }
    double getExtI135() { return extI135; }
    double getExtXe135() { return extXe135; }

    public void setRadialShape(double shape) {
        radialShape = shape;
    }

    public double getRadialShape() {
        return radialShape;
    }

    public double getWaterInflowTemperature() {
        return waterInflowTemperature;
    }

        public void setDrain(Connectable drain) {
        drum = drain;
    }

    @Override
    public double getPressure() {
        return pressure;
    }

    @Override
    public double getSteamDensity() {
        return steamDensity;
    }

    public double getWaterMass() {
        return waterMass;
    }

    @Override
    public double getWaterLevel() {
        return waterLevel;
    }

    @Override
    public double getSteamOutflowRate() {
        return steamOutflowRate * 20;
    }

    @Override
    public double getWaterTemperature() {
        return waterTemperature;
    }

    public double getBoilingPoint() {
        return boilingPoint;
    }

    @Override
    public double getWaterDensity() {
        return specificDensityWater;
    }

    public double getWaterOutflow() {
        return waterOutflow;
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
    public void updateSteamInflow(double flow, double tempC) {
        // if this happens we have bigger problems

    }

    @Override
    public void updateWaterOutflow(double flow, double tempC) {
        // unnecassary at this point
    }

    @Override
    public void updateSteamOutflow(double flow, double tempC) {
        steamOutFlow = flow;
        // exception to where usually this is done in the update method rather than
        // called by an external object
        steamMass -= steamOutFlow;
    }

    @Override
    public void updateWaterInflow(double flow, double tempC) {
        waterInflow = flow;
        waterInflowTemperature = tempC;
    }

    @Override
    public double getSteamTemperature() {
        return steamTemperature;
    }

    @Override
    public double getWaterOutflowRate() {
        return waterOutflowRate * 20;
    }

    @Override
    public double getWaterInflowRate() {
        return waterInflowRate * 20;
    }

    public double getSteamProduction() {
        return steamProduction * 20;
    }

    @Override
    public void updateTableValues() {
        uiData.setTableData(new Object[][] {
                { Localization.t("Type:"), uiData.name, "" },
                { Localization.t("Thermal Power:"), NPPMath.round(thermalPower * 1000), "kW" },
                { Localization.t("Clad Temp:"), NPPMath.round(cladTempC), "C" },
                { Localization.t("Dryout:"), NPPMath.round((1.0 - wetFraction) * 100), "%" },
                { Localization.t("Pressure:"), NPPMath.round(pressure), "Mpa" },
                { Localization.t("Water Inflow:"), NPPMath.round(waterInflowRate * 20), "kg/s" },
                { Localization.t("Inlet Temperature:"), NPPMath.round(waterInflowTemperature), "C" },
                { Localization.t("Outlet Temperature:"), NPPMath.round(waterTemperature), "C" },
                { Localization.t("Voiding:"), NPPMath.round(voidFraction * 100), "%" },
                { Localization.t("I135:"), NPPMath.round(i135Count / 1.93458087e20 * 100), "%",},
                { Localization.t("Xe135:"), NPPMath.round(xe135Count / 4.6978565e19 * 100), "%" }
        });
    }

    public double getVoidFraction() {
        return voidFraction;
    }

    public void setThermalPower(double power) {
        thermalPower = power;
    }

    public void setWaterTemp(double tempC) {
        waterTemperature = tempC;
        // 金属节点必须同步：否则 setWaterTemp(270) 之后 clad 还停在 20 °C，
        // 两节点模型会算出 −36 MW 的假换热，0.7 s 内把水抽到 0 °C 以下，蒸汽表越界。
        cladTempC = tempC;
    }

    public void setSteamPressure(double press) {
        pressure = press;
    }

    @Override
    public double getSteamInflowRate() {
        return 0;
    }
    
    public double getThermalPower() {
        return thermalPower;
    }
}
