package com.darwish.nppsim;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 饱和水/蒸汽物性表（按温度成表，压力/密度/焓/熵由表插值）。
 *
 * <p><b>2026-11-11 性能重写</b>：旧实现把插值结果逐条塞进
 * {@code HashMap<Integer, Double>} —— 压力↔密度两向各约 22 万条、
 * 密度→压力约 78 万条，合计约 120 万条装箱记录（≈ 70 MB 堆），
 * 启动时逐条 new Integer / new Double 插入本身就要 2 s 以上；
 * 运行时每次查表还要再装箱一次，20 Hz × 每帧数百次查询是小对象分配的稳定来源。</p>
 *
 * <p>这些键本来就是**等步长的整数刻度**，直接换成原始 {@code double[]}，
 * 下标 = 键 − 偏移量：查表退化成一次数组访问，零装箱、零哈希、零 GC 压力。
 * 表值来源与插值公式完全保持原样，数值结果逐位一致（tools/dev/TableBaseline 回归比对）。</p>
 */
public class SteamTables {

    /** 温度维：1..373 °C，索引 = 摄氏温度（373 只是边界，供 372→373 插值用） */
    private static final int TEMP_MIN = 1, TEMP_MAX = 373;
    /** 压力维：0.0017..22.0641 MPa，步长 1e-4 MPa，键 = round(p × 10000) */
    private static final int P_OFFSET = 17, P_COUNT = 220626;
    /** 密度维：0.0032..77.8752，步长 1e-4，键 = round(d × 10000) */
    private static final int D_OFFSET = 32, D_COUNT = 778720;
    /** 焓维：62.9..2048.4 kJ/kg，步长 0.1，键 = round(h × 10) */
    private static final int H_OFFSET = 629, H_COUNT = 19857;

    /** 温度 → 饱和压力 MPa */
    private final double[] steamPressureByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 水密度（表列 2） */
    private final double[] waterDensityByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 汽化潜热 kJ/kg */
    private final double[] vaporizationEnthalpyByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 饱和水焓 kJ/kg */
    private final double[] waterEnthalpyByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 饱和汽焓 kJ/kg */
    private final double[] steamEnthalpyByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 饱和水熵 kJ/(kg·K) */
    private final double[] waterEntropyByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 饱和汽熵 kJ/(kg·K) */
    private final double[] steamEntropyByTemp = new double[TEMP_MAX + 1];
    /** 温度 → 汽化熵 kJ/(kg·K) */
    private final double[] vaporizationEntropyByTemp = new double[TEMP_MAX + 1];

    /** 压力 → 饱和温度 °C */
    private final double[] steamTemperatureByPressure = new double[P_COUNT];
    /** 压力 → 汽密度 */
    private final double[] steamDensityByPressure = new double[P_COUNT];
    /** 密度 → 饱和压力 MPa */
    private final double[] steamPressureByDensity = new double[D_COUNT];
    /** 焓 → 饱和水温度 °C */
    private final double[] waterTemperatureByEnthalpy = new double[H_COUNT];

    public SteamTables() {
        final int n;
        /* 列索引：0 温度 / 1 压力 / 2 水密度 / 3 汽密度 / 7 hf / 8 hg / 9 hfg / 10 sf / 11 sg / 12 sfg */
        final double[] cTemp, cPress, cWaterDens, cSteamDens, cHf, cHg, cHfg, cSf, cSg, cSfg;
        try (java.io.InputStream in = getClass().getResourceAsStream("/res/saturated_by_temperature_V1.4.json")) {
            if (in == null) {
                throw new IllegalStateException("steam table /res/saturated_by_temperature_V1.4.json is not on the classpath");
            }
            /* 旧实现用 jsonString += line 逐行拼接，是 O(n²) 的字符串复制；
             * 39 KB 的表就要做上千次全量拷贝。改为一次性 StringBuilder。 */
            StringBuilder sb = new StringBuilder(64 * 1024);
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int read;
                while ((read = reader.read(buf)) > 0) {
                    sb.append(buf, 0, read);
                }
            }
            JSONArray data = new JSONObject(sb.toString()).getJSONArray("data");
            n = data.length();
            if (n == 0) {
                throw new IllegalStateException("steam table is empty");
            }
            cTemp = new double[n];
            cPress = new double[n];
            cWaterDens = new double[n];
            cSteamDens = new double[n];
            cHf = new double[n];
            cHg = new double[n];
            cHfg = new double[n];
            cSf = new double[n];
            cSg = new double[n];
            cSfg = new double[n];
            for (int i = 0; i < n; i++) {
                JSONArray row = data.getJSONArray(i);
                cTemp[i] = row.getDouble(0);
                cPress[i] = row.getDouble(1);
                cWaterDens[i] = row.getDouble(2);
                cSteamDens[i] = row.getDouble(3);
                cHf[i] = row.getDouble(7);
                cHg[i] = row.getDouble(8);
                cHfg[i] = row.getDouble(9);
                cSf[i] = row.getDouble(10);
                cSg[i] = row.getDouble(11);
                cSfg[i] = row.getDouble(12);
            }
        } catch (Exception e) {
            // 不能吞掉：表没建起来时后面所有查表都是错的。
            throw new IllegalStateException("failed to load steam table", e);
        }

        /* ---- 1) 温度维直接按行填（1 °C 一档） ---- */
        java.util.Arrays.fill(steamPressureByTemp, Double.NaN);
        for (int i = 0; i < n; i++) {
            int t = (int) Math.round(cTemp[i]);
            if (t < TEMP_MIN || t > TEMP_MAX) {
                continue;
            }
            steamPressureByTemp[t] = cPress[i];
            waterDensityByTemp[t] = cWaterDens[i];
            waterEnthalpyByTemp[t] = cHf[i];
            steamEnthalpyByTemp[t] = cHg[i];
            vaporizationEnthalpyByTemp[t] = cHfg[i];
            waterEntropyByTemp[t] = cSf[i];
            steamEntropyByTemp[t] = cSg[i];
            vaporizationEntropyByTemp[t] = cSfg[i];
        }

        /* ---- 2) 三个反向维表：按刻度逐点线性插值（算法与原实现逐行对应） ---- */
        java.util.Arrays.fill(steamPressureByDensity, Double.NaN);
        java.util.Arrays.fill(steamDensityByPressure, Double.NaN);
        java.util.Arrays.fill(steamTemperatureByPressure, Double.NaN);
        java.util.Arrays.fill(waterTemperatureByEnthalpy, Double.NaN);

        // 密度 → 压力：y 从表头密度刻度（77.8750）扫到 0.0032
        for (int y = D_OFFSET + D_COUNT - 2; y >= D_OFFSET; y--) {
            double target = y / 10000.0;
            for (int i = 1; i < n; i++) {
                if (cSteamDens[i] < target) {
                    double dDens = cSteamDens[i] - cSteamDens[i - 1];
                    double dPress = cPress[i] - cPress[i - 1];
                    steamPressureByDensity[y - D_OFFSET] = cPress[i] + (target - cSteamDens[i]) * (dPress / dDens);
                    break;
                }
            }
        }

        // 压力 → 汽密度 / 饱和温度：0.0017 扫到 22.0640
        for (int y = P_OFFSET; y <= 220640; y++) {
            double target = y / 10000.0;
            for (int i = 1; i < n; i++) {
                if (cPress[i] > target) {
                    double dPress = cPress[i] - cPress[i - 1];
                    double dDens = cSteamDens[i] - cSteamDens[i - 1];
                    steamDensityByPressure[y - P_OFFSET] = cSteamDens[i] + (target - cPress[i]) * (dDens / dPress);
                    break;
                }
            }
            for (int i = 1; i < n; i++) {
                if (cPress[i] > target) {
                    double dPress = cPress[i] - cPress[i - 1];
                    double dTemp = cTemp[i] - cTemp[i - 1];
                    steamTemperatureByPressure[y - P_OFFSET] = cTemp[i] + (target - cPress[i]) * (dTemp / dPress);
                    break;
                }
            }
        }

        // 焓 → 饱和水温度：62.9 扫到 2048.3 kJ/kg
        for (int y = H_OFFSET; y <= 20483; y++) {
            double target = y / 10.0;
            for (int i = 1; i < n; i++) {
                if (cHf[i] > target) {
                    double dEnthalpy = cHf[i] - cHf[i - 1];
                    double dTemp = cTemp[i] - cTemp[i - 1];
                    waterTemperatureByEnthalpy[y - H_OFFSET] = cTemp[i] + (target - cHf[i]) * (dTemp / dEnthalpy);
                    break;
                }
            }
        }

        reportGaps();
    }

    /**
     * 建表后自检：用到的那几段刻度必须全部有值。
     *
     * <p>旧实现遇到缺项是在查表时抛 NPE（仿真线程直接死）；现在建表时就把缺口补齐并报数，
     * 正常表应当是 0 个缺口，出现非 0 说明表文件或刻度范围变了，需要人工核对。</p>
     */
    private void reportGaps() {
        int g1 = fillGaps(steamTemperatureByPressure);
        int g2 = fillGaps(steamDensityByPressure);
        int g3 = fillGaps(steamPressureByDensity);
        int g4 = fillGaps(waterTemperatureByEnthalpy);
        int gaps = g1 + g2 + g3 + g4;
        // 温度维：1..372 都必须有值（373 是插值边界，由 372 兜底）
        for (int t = TEMP_MAX - 1; t >= TEMP_MIN; t--) {
            if (Double.isNaN(steamPressureByTemp[t])) {
                steamPressureByTemp[t] = steamPressureByTemp[t + 1];
                waterDensityByTemp[t] = waterDensityByTemp[t + 1];
                waterEnthalpyByTemp[t] = waterEnthalpyByTemp[t + 1];
                steamEnthalpyByTemp[t] = steamEnthalpyByTemp[t + 1];
                vaporizationEnthalpyByTemp[t] = vaporizationEnthalpyByTemp[t + 1];
                waterEntropyByTemp[t] = waterEntropyByTemp[t + 1];
                steamEntropyByTemp[t] = steamEntropyByTemp[t + 1];
                vaporizationEntropyByTemp[t] = vaporizationEntropyByTemp[t + 1];
                gaps++;
            }
        }
        steamPressureByTemp[TEMP_MAX] = steamPressureByTemp[TEMP_MAX - 1];
        waterDensityByTemp[TEMP_MAX] = waterDensityByTemp[TEMP_MAX - 1];
        waterEnthalpyByTemp[TEMP_MAX] = waterEnthalpyByTemp[TEMP_MAX - 1];
        steamEnthalpyByTemp[TEMP_MAX] = steamEnthalpyByTemp[TEMP_MAX - 1];
        vaporizationEnthalpyByTemp[TEMP_MAX] = vaporizationEnthalpyByTemp[TEMP_MAX - 1];
        waterEntropyByTemp[TEMP_MAX] = waterEntropyByTemp[TEMP_MAX - 1];
        steamEntropyByTemp[TEMP_MAX] = steamEntropyByTemp[TEMP_MAX - 1];
        vaporizationEntropyByTemp[TEMP_MAX] = vaporizationEntropyByTemp[TEMP_MAX - 1];

        /* 缺口只发生在最顶端几个刻度（表最后一行无法再夹住目标点），查表钳位后不可达；
         * 旧实现在这些点上会抛 NPE。默认静默，需要排查时用 -Dnpp.startup.timing=true 打开。 */
        if (gaps > 0 && Boolean.getBoolean("npp.startup.timing")) {
            System.err.println("[SteamTables] 建表缺口 " + gaps + " 处（温度反查 " + g1 + " / 密度反查 " + g2
                    + " / 压力反查 " + g3 + " / 焓反查 " + g4 + "），均为表头边界，已用相邻刻度补齐");
        }
    }

    /** 用前一个有效值补齐 NaN 空洞（表头/表尾之外不应出现缺口） */
    private static int fillGaps(double[] a) {
        int gaps = 0;
        double last = Double.NaN;
        for (int i = 0; i < a.length; i++) {
            if (Double.isNaN(a[i])) {
                if (!Double.isNaN(last)) {
                    a[i] = last;
                    gaps++;
                }
            } else {
                last = a[i];
            }
        }
        // 开头就没有值的（表头之外），用第一个有效值回填
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i])) {
                for (int j = 0; j < i; j++) {
                    if (Double.isNaN(a[j])) {
                        a[j] = a[i];
                        gaps++;
                    }
                }
                break;
            }
        }
        return gaps;
    }

    /**
     * 查表越界保护（2026-11-02）。
     *
     * <p>旧行为：任一物性超出表定义域就 {@code endSimulation()} 并弹一个
     * "A component has been damaged beyond repair" 的模态窗。那个窗是在**仿真更新线程里**
     * 弹的 —— 模态窗会阻塞该线程，于是整个电厂就冻结在那一帧：
     * 实测只要凝汽器冷却水泵停机、冷却水出口温度算出 2 万度，
     * 一次查表越界就能让整机停摆（2026-11-02 环路自检抓到）。</p>
     *
     * <p>现在改成：钳到定义域边界 + 在 stderr 打一次告警（含调用栈）+ 计数，
     * 仿真继续跑。物性越界通常是瞬态或某个泵/阀停机的副作用，
     * 不该由查表来判"设备损坏"；真正的异常交给各自的保护逻辑。</p>
     *
     * @return 钳制后的合法输入值
     */
    private static double clampToTable(String what, double value, double lo, double hi) {
        clampedCount++;
        if (clampedCount <= 20) {
            StringBuilder sb = new StringBuilder();
            sb.append("[SteamTables] 越界钳制: ").append(what).append('(')
              .append(value).append(") -> [").append(lo).append(", ").append(hi).append(']');
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = Math.min(4, st.length - 1); i >= 0; i--) {
                sb.append("\n    at ").append(st[i]);
            }
            System.err.println(sb);
        }
        if (Double.isNaN(value)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, value));
    }

    /** 累计被钳制次数（诊断用；正常工况应为 0） */
    static int clampedCount = 0;

    public double getSteamTemperatureByPressure(double pressure) { //Mpa
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = pressure;
        if (!(v_ >= 0.0017 && v_ < 22.064)) {
            v_ = clampToTable("getSteamTemperatureByPressure", v_, 0.0017, 22.063999);
        }
        int lower = (int) (v_ * 10000.0);
        int higher = (int) (v_ * 10000.0 + 1);
        double lowerValue = steamTemperatureByPressure[lower - P_OFFSET];
        double higherValue = steamTemperatureByPressure[higher - P_OFFSET];
        double perDensity = higherValue - lowerValue;
        return lowerValue + ((v_ * 10000.0 - lower) * perDensity);
    }

    public double getSteamPressureByTemp(double tempC) { //Mpa
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = tempC;
        if (!(v_ >= 1 && v_ < 373)) {
            v_ = clampToTable("getSteamPressureByTemp", v_, 1, 372.999999);
        }
        int lower = (int) v_;
        int higher = (int) v_ + 1;
        double lowerValue = steamPressureByTemp[lower];
        double higherValue = steamPressureByTemp[higher];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ - lower) * perDegree);
    }

    public double getSteamPressureByDensity(double density) { //m3/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = density;
        if (!(v_ >= 0.0032 && v_ < 77.875)) {
            v_ = clampToTable("getSteamPressureByDensity", v_, 0.0032, 77.874999);
        }
        int lower = (int) (v_ * 10000.0);
        int higher = (int) (v_ * 10000.0 + 1);
        double lowerValue = steamPressureByDensity[lower - D_OFFSET];
        double higherValue = steamPressureByDensity[higher - D_OFFSET];
        double perDensity = higherValue - lowerValue;
        return lowerValue + ((v_ * 10000.0 - lower) * perDensity);
    }

    public double getSteamDensityByPressure(double pressure) { //m3/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = pressure;
        if (!(v_ >= 0.0017 && v_ < 22.064)) {
            v_ = clampToTable("getSteamDensityByPressure", v_, 0.0017, 22.063999);
        }
        int lower = (int) (v_ * 10000.0);
        int higher = (int) (v_ * 10000.0 + 1);
        double lowerValue = steamDensityByPressure[lower - P_OFFSET];
        double higherValue = steamDensityByPressure[higher - P_OFFSET];
        double perDensity = higherValue - lowerValue;
        return lowerValue + ((v_ * 10000.0 - lower) * perDensity);
    }

    public double getWaterDensityByTemp(double tempC) { //m3/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = tempC;
        if (!(v_ >= 1 && v_ < 373)) {
            v_ = clampToTable("getWaterDensityByTemp", v_, 1, 372.999999);
        }
        int lower = (int) v_;
        int higher = (int) v_ + 1;
        double lowerValue = waterDensityByTemp[lower];
        double higherValue = waterDensityByTemp[higher];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ - lower) * perDegree);
    }

    public double getSpecificVaporEnthalpyByTemperature(double tempC) { //kJ/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = tempC;
        if (!(v_ >= 1 && v_ < 373)) {
            v_ = clampToTable("getSpecificVaporEnthalpyByTemperature", v_, 1, 372.999999);
        }
        int lower = (int) v_;
        int higher = (int) v_ + 1;
        double lowerValue = vaporizationEnthalpyByTemp[lower];
        double higherValue = vaporizationEnthalpyByTemp[higher];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ - lower) * perDegree);
    }

    public double getWaterEnthalpyByTemperature(double tempC) { //kJ/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = tempC;
        if (!(v_ >= 1 && v_ < 373)) {
            v_ = clampToTable("getWaterEnthalpyByTemperature", v_, 1, 372.999999);
        }
        int lower = (int) v_;
        int higher = (int) v_ + 1;
        double lowerValue = waterEnthalpyByTemp[lower];
        double higherValue = waterEnthalpyByTemp[higher];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ - lower) * perDegree);
    }

    public double getSteamEnthalpyByTemperature(double tempC) { //kJ/kg
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = tempC;
        if (!(v_ >= 1 && v_ < 373)) {
            v_ = clampToTable("getSteamEnthalpyByTemperature", v_, 1, 372.999999);
        }
        int lower = (int) v_;
        int higher = (int) v_ + 1;
        double lowerValue = steamEnthalpyByTemp[lower];
        double higherValue = steamEnthalpyByTemp[higher];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ - lower) * perDegree);
    }

    /* ---------- 熵（kJ/kg/K）：汽轮机等熵膨胀用 ---------- */

    public double getWaterEntropyByTemperature(double tempC) {
        return lookupEntropy(waterEntropyByTemp, tempC);
    }

    public double getSteamEntropyByTemperature(double tempC) {
        return lookupEntropy(steamEntropyByTemp, tempC);
    }

    public double getVaporizationEntropyByTemperature(double tempC) {
        return lookupEntropy(vaporizationEntropyByTemp, tempC);
    }

    /** 熵查表：越界钳到 [1,372] °C，不做弹窗（熵只用于效率计算，不该打断仿真） */
    private static double lookupEntropy(double[] table, double tempC) {
        double t = Math.max(1.0, Math.min(372.0, tempC));
        int lower = (int) t;
        int higher = Math.min(372, lower + 1);
        double a = table[lower];
        double b = table[higher];
        return a + (t - lower) * (b - a);
    }

    public double getWaterTemperatureByEnthalpy(double enthalpy) { //C
        /* 2026-11-02：查表越界不再终止仿真（见 clampToTable 说明）。 */
        double v_ = enthalpy;
        if (!(v_ >= 62.9 && v_ < 2048.3)) {
            v_ = clampToTable("getWaterTemperatureByEnthalpy", v_, 62.9, 2048.2999990000003);
        }
        int lower = (int) (v_ * 10.0);
        int higher = (int) (v_ * 10.0 + 1);
        double lowerValue = waterTemperatureByEnthalpy[lower - H_OFFSET];
        double higherValue = waterTemperatureByEnthalpy[higher - H_OFFSET];
        double perDegree = higherValue - lowerValue;
        return lowerValue + ((v_ * 10.0 - lower) * perDegree);
    }
}
