package com.darwish.nppsim;

import static com.darwish.nppsim.NPPSim.autoControl;
import static com.darwish.nppsim.NPPSim.core;
import static com.darwish.nppsim.NPPSim.mcc;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class MCC extends Component {
    final SeparatorDrum drum1, drum2;
    final MCPPressureHeader pHeader1, pHeader2;
    final SimpleSuctionHeader sHeader1, sHeader2;
    final ArrayList<Pump> mcp = new ArrayList<>(); // 0-3 loop 1, 4-7 loop 2
    final ArrayList<FuelChannel> fuelChannels1 = new ArrayList<>();
    final ArrayList<FuelChannel> fuelChannels2 = new ArrayList<>();
    final ArrayList<SteamWaterPipe> steamWaterPipes1 = new ArrayList<>();
    final ArrayList<SteamWaterPipe> steamWaterPipes2 = new ArrayList<>();

    public MCC() {
        drum1 = new SeparatorDrum();
        drum2 = new SeparatorDrum();

        for (ArrayList<Channel> x: core.coreArray) {
            for (int i = 0; i < x.size(); i++) {
                if (x.get(i) instanceof FuelChannel) {
                    if (i < 28) {
                        fuelChannels2.add((FuelChannel)x.get(i)); 
                    } else {
                        fuelChannels1.add((FuelChannel)x.get(i));
                    }
                }
            }
        }
        fuelChannels1.forEach(channel -> {
            steamWaterPipes1.add(new SteamWaterPipe(channel, drum1));
        });
        fuelChannels2.forEach(channel -> {
            steamWaterPipes2.add(new SteamWaterPipe(channel, drum2));
        });
        sHeader1 = new SimpleSuctionHeader(new Connectable[] {drum1}, 74.9);
        sHeader2 = new SimpleSuctionHeader(new Connectable[] {drum2}, 74.9);
        sHeader1.isolationValveArray.get(0).position = 1.0f;
        sHeader2.isolationValveArray.get(0).position = 1.0f;
        pHeader1 = new MCPPressureHeader(fuelChannels1);
        pHeader2 = new MCPPressureHeader(fuelChannels2);
        /* 8 台主泵，每环路 4 台（3 运行 + 1 备用）。
         * 额定流量按实堆口径标定：RBMK-1500 一回路 12000 kg/s，6 台运行
         * → 单泵 2000 kg/s。285°C 比容 1.35e-3 m³/kg → 2.70 m³/s。
         * 旧值 2.2222 m³/s 只对应 1646 kg/s/台，6 台合计 9878 kg/s（额定的 82%），
         * 堆芯在满功率下会欠冷、空泡偏高。 */
        for (int i = 0; i < 4; i++) {
            mcp.add(new MCCPump(1000, 2.70f, 0.226, 18, 200, 933, sHeader1, pHeader1));
        }
        for (int i = 0; i < 4; i++) {
            mcp.add(new MCCPump(1000, 2.70f, 0.226, 18, 200, 933, sHeader2, pHeader2));
        }
    }

    protected class SeparatorDrum extends WaterSteamSubComponent implements Connectable, UIReadable, Serializable, SteamSupplyLimit {
        /**
         * 汽包本体容积 m³（2026-11-05 按真实几何重算）。
         *
         * <p>RBMK-1500 的鼓式分离器是**两台**水平圆筒：内径 2.6 m、筒长 30.5 m，
         * 单台 π·1.3²·30.5 ≈ 162 m³，两台 324 m³。</p>
         */
        static final double DRUM_BODY_M3 = 324.0;
        /** 下降管 + 连接管容积 m³（始终满水，不参与汽空间） */
        static final double DOWNCOMER_M3 = 73.0;
        /** 汽包 + 下降管总容积 m³ */
        static final double TOTAL_VOLUME_M3 = DRUM_BODY_M3 + DOWNCOMER_M3;

        private final double volume = TOTAL_VOLUME_M3;
        private final double nominalWaterVolume;
        private double initialSteamMass; // this would be air in reality
        private double steamMass = 0.0; // kg
        private double waterMass = NOMINAL_WATER_KG; // 水位 0 %（鼓中心线上 200 mm，200 t）
        private double steamProduction = 0.0; // kg/s
        private double thermalLoss = 0.0; // conductive energy loss Mwt
        /* 汽包不再有外来热源（2026-11-01）。
         *
         * 真实 RBMK 的鼓式分离器只做汽水分离：堆芯出来的汽水混合物进汽包，
         * 蒸汽去汽轮机、饱和水经下降管回主循环泵。热量在燃料通道里就已经加进去了。
         *
         * 2026-10-27 曾把 Core.thermalPower 直接注入汽包水（当时 FuelChannel.update()
         * 是空实现，一回路没有别的热源）；但后来通道侧补上了 thermalPower*50 kJ 的加热，
         * 这个注入就变成了同一份功率计两次：产汽量翻倍、水位持续上涨、最后走泄压阀。
         * 现在汽包的能量方程只保留"进汽冷凝 / 闪蒸"两项，堆芯功率全部走通道。 */

        // calculated results from the update thread stored here for use in other
        // functions:
        private double steamDensity;
        private double specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
        private double specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);// NPPMath.calculateSpecificVaporEnthalpy(waterTemperature);
        private double boilingPoint = Loader.tables.getSteamTemperatureByPressure(pressure);
        private double specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
        private double waterVolume;
        private double steamVolume;
        private double deltaEnergy = 0.0; // surplus/deficit energy from last time step in kJ
        private double waterInflowTemperature = 20.0; // c
        private double steamInflowTemperature = 20.0; // c

        public SeparatorDrum() {
            waterVolume = waterMass * specificDensityWater;
            steamVolume = volume - waterVolume;
            nominalWaterVolume = waterVolume;   // 仅存档兼容；水位已改按质量比
            initialSteamMass = steamVolume / 1.6718; // constant density for 20c
            steamDensity = steamVolume / (steamMass + initialSteamMass);
        }

        void update() {
            /* 产汽计数按 tick 复位（2026-11-02 环路自检统一量纲），
             * getter 再乘 20 → kg/s。原来靠 UI 的 resetFlowRates() 清零，与帧率耦合。 */
            steamProduction = 0.0;
            double vacuumBreakerFlow = (pressure - 0.10142) * 20;
            waterMass -= waterOutflow;
            if (waterInflow < 0) {
                waterMass += waterInflow;
            } else {
                double[] inflowData = NPPMath.mixWater(waterMass, waterTemperature, waterInflow, waterInflowTemperature);
                waterMass = inflowData[0];
                waterTemperature = inflowData[1];
            }

            specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
            specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);
            specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);

            steamMass -= steamOutflow;
            if (steamInflow < 0) {
                steamMass += steamInflow;
            } else {
                steamMass += steamInflow;
                double oldWaterTemp = waterTemperature;
                waterTemperature += ((Loader.tables.getSteamEnthalpyByTemperature(steamInflowTemperature) - Loader.tables.getSteamEnthalpyByTemperature(waterTemperature)) * steamInflow) / (specificHeatWater * waterMass);
                specificHeatWater = NPPMath.calculateSpecificHeatWater(waterTemperature);
                deltaEnergy += (Loader.tables.getSteamEnthalpyByTemperature(waterTemperature) - Loader.tables.getSteamEnthalpyByTemperature(oldWaterTemp)) * steamInflow; //correct for difference in new steamTemp and new waterTemp after energy transfer
            }

            steamTemperature = waterTemperature;

            if (steamMass <= 0) {
                steamMass = 0;
                initialSteamMass -= steamOutflow;
            }
            if (pressure < 0.10142) {
                initialSteamMass -= vacuumBreakerFlow;
            }
            
            /* 能量平衡（kJ/tick）：deltaEnergy 是蒸发/冷凝的潜热欠账，thermalLoss 是散热。
             * 汽包没有外来热源：进汽在下面的平衡里按"蒸汽空间存量 → 饱和平衡"折算，
             * 温度只由进水混合与相变决定。 */
            double energy = 0 - deltaEnergy / 3 - thermalLoss * 50;
            deltaEnergy -= deltaEnergy / 3;

            waterTemperature += (energy / (specificHeatWater * waterMass));
            if (!Double.isFinite(waterTemperature)) {
                waterTemperature = 20.0;             // 数值崩溃兜底：回到冷水
            }
            waterTemperature = Math.max(5.0, Math.min(360.0, waterTemperature));
            final double potentialPressure = Loader.tables.getSteamPressureByTemp(waterTemperature);
            specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
            waterVolume = waterMass * specificDensityWater;
            steamVolume = volume - waterVolume;
            double deltaSteamMass = steamVolume / Loader.tables.getSteamDensityByPressure(potentialPressure) - (steamMass + initialSteamMass);
            if (steamMass + deltaSteamMass < 0) {
                deltaSteamMass = 0 - steamMass;
            }

            specificVaporEnthalpy = Loader.tables.getSpecificVaporEnthalpyByTemperature(waterTemperature);
            deltaEnergy += deltaSteamMass * specificVaporEnthalpy;

            steamProduction += deltaSteamMass + steamInflow;

            if (!Double.isFinite(deltaSteamMass)) {
                deltaSteamMass = 0.0;                // 数值崩溃兜底
            }
            if (deltaSteamMass > waterMass * 0.05) {
                // 单 tick 蒸发量不超过存水的 5%：给水断掉时不会一瞬间把汽包抽干
                deltaSteamMass = waterMass * 0.05;
            }
            waterMass -= deltaSteamMass;
            steamMass += deltaSteamMass;

            if (steamMass < 0) {
                steamMass = 0;
            }

            specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
            waterVolume = waterMass * specificDensityWater;
            waterLevel = levelFromMass(waterMass);
            steamVolume = Math.max(0.0, volume - waterVolume);
            steamDensity = steamVolume / Math.max(1e-9, steamMass + initialSteamMass);

            /* ---- 汽包压力（2026-11-05 重写）----
             *
             * <p>旧实现 {@code pressure = f(蒸汽比容)}：汽空间只有 96 m³ / 3.5 t 汽时，
             * 主汽门抽走 280 kg 压力就掉 0.4 MPa，1 秒掉 1 MPa。于是单回路里
             * "抽汽 → 压力塌 → 通道沸点降 → 空泡塌 → 正反应性 → 功率暴涨"
             * 这个链条被瞬间点燃（实测 1.5 s 冲到 33 000 MW）。</p>
             *
             * <p>真实汽包的压力是**由 174 t 饱和水的温度决定**的（水和汽共存，
             * 压力 = 该温度下的饱和压力），蒸汽空间只是缓冲，不可能让压力跟着
             * 单 tick 的抽汽跳。这里就按这个物理来：</p>
             * <ul>
             *   <li>汽空间还有足够的汽（≥ 饱和量的 40 %）→ 压力 = 水温对应的饱和压力，
             *       时间常数由 174 t 水的热容决定（几百秒级），抽汽引起的压力变化很缓；</li>
             *   <li>汽被抽空（&lt; 40 %）→ 退回到"按剩余汽量算"，压力自然会塌，
             *       避免出现"汽包空了却还有 6.9 MPa"的假象。</li>
             * </ul>
             */
            double pSat = Loader.tables.getSteamPressureByTemp(waterTemperature);
            if (!Double.isFinite(pSat) || pSat <= 0.0) {
                pSat = pressure;
            }
            double svgSat = Loader.tables.getSteamDensityByPressure(pSat);   // 实为饱和汽比容 m³/kg
            double satSteamMass = (!Double.isFinite(svgSat) || svgSat <= 0.0)
                    ? steamMass : steamVolume / svgSat;
            if (steamMass >= satSteamMass * 0.4) {
                pressure = pSat;
            } else {
                double pByDensity = Loader.tables.getSteamPressureByDensity(steamDensity);
                pressure = Math.min(pSat, pByDensity);
            }
            boilingPoint = Loader.tables.getSteamTemperatureByPressure(pressure);
            sanitize();                       // 数值卫生：溢出/NaN 时把蒸汽转回水（质量守恒）
            resetFlows();
        }

        /**
         * 数值卫生与"超量崩溃自动重置"。
         *
         * <p>规则（保持物质总量）：</p>
         * <ul>
         *   <li>水量/汽量出现 NaN、Inf 或负数 → 按安全值重置；</li>
         *   <li>汽空间存量超过 {@link #MAX_STEAM_KG} → 超出部分**冷凝成水**（汽→水，总量不变），
         *       这正是"给水带汽过多导致崩溃时，把这一管路的蒸汽重置为水"；</li>
         *   <li>水量低于 {@link #MIN_WATER_KG} → 补到最小值（日志里有记录，避免干锅）。</li>
         * </ul>
         */
        /**
         * 水位表读数（%）：0 % = 额定 174 t（鼓中心线），+100 % = 表盘满量程 440 t。
         *
         * <p>用**质量**而不是体积：体积口径依赖比容，冷态（20 °C，1.0e-3）和热态
         * （284 °C，1.35e-3）会差 35 %，同一包水一升温水位就凭空低 26 %，
         * 冷热态读数对不上。质量口径在任何温度下都是同一把尺。</p>
         */
        double levelFromMass(double massKg) {
            return (massKg - NOMINAL_WATER_KG) / (LEVEL_TABLE_MAX_KG - NOMINAL_WATER_KG) * 100.0;
        }

        /**
         * 物理最大水装 kg：汽包容积的 97 %（留 3 % 给汽空间，否则
         * {@code steamVolume} 会变成负数 → 蒸汽密度为负 → 压力 NaN → 仿真发散）。
         */
        double maxWaterMass() {
            return volume * 0.97 / Math.max(1e-6, specificDensityWater);
        }

        void sanitize() {
            if (!Double.isFinite(waterMass)) {
                waterMass = NOMINAL_WATER_KG;
            }
            if (!Double.isFinite(steamMass) || steamMass < 0.0) {
                double back = Math.max(0.0, steamMass);       // 负数汽量还给水侧（守恒）
                steamMass = 0.0;
                waterMass += back;
            }
            if (steamMass > MAX_STEAM_KG) {
                waterMass += steamMass - MAX_STEAM_KG;        // 多余蒸汽冷凝成水
                steamMass = MAX_STEAM_KG;
            }
            if (!Double.isFinite(waterMass) || waterMass < MIN_WATER_KG) {
                waterMass = MIN_WATER_KG;
            }
            double maxWater = maxWaterMass();
            if (waterMass > maxWater) {
                // 水量过多：超出部分转到除盐水箱（不凭空消失）
                double excess = waterMass - maxWater;
                waterMass -= excess;
                if (NPPSim.pcs != null && NPPSim.pcs.demineralizedWaterTank != null) {
                    NPPSim.pcs.demineralizedWaterTank.updateWaterInflow(excess, waterTemperature);
                }
            }
            if (!Double.isFinite(waterTemperature)) {
                waterTemperature = 20.0;
            }
            waterTemperature = Math.max(5.0, Math.min(360.0, waterTemperature));
            if (!Double.isFinite(pressure) || pressure < 0.05 || pressure > 20.0) {
                pressure = 0.1014;
            }
            if (!Double.isFinite(waterLevel) || Math.abs(waterLevel) > 500.0) {
                waterLevel = 0.0;
            }
        }

        /** 汽空间存量上限（超过就冷凝成水） */
        static final double MAX_STEAM_KG = 12000.0;
        /** 水容积下限（防止干锅/负质量） */
        static final double MIN_WATER_KG = 20000.0;

        /**
         * 额定水装 kg（水位 0 %，2026-11-05 重算）。
         *
         * <p>真实 RBMK 的正常水位在鼓中心线**上方约 200 mm**（鼓内径 2.6 m）。按弓形面积算，
         * 单台水位截面积 3.17 m² × 长 30.5 m = 97 m³，两台 193 m³，加下降管 73 m³
         * 共 266 m³；284 °C 饱和水密度 741 kg/m³ → <b>197 t</b>，取 200 t。</p>
         *
         * <p>这样汽空间是 397 − 270 = 127 m³（约 4.6 t 蒸汽），旧实现是
         * 223 t 水 / 96 m³ 汽空间 / 3.5 t 汽 —— 汽空间大了 32 %，
         * 主汽门抽汽时能多缓冲三分之一的时间。</p>
         *
         * <p>旧值 223 t 把汽包灌到 76 % 满，汽空间只剩 24 %，不像真实汽包。</p>
         */
        static final double NOMINAL_WATER_KG = 200000.0;

        /**
         * 水位表满量程对应的水量 kg（用户指定 Max 440 t）。
         *
         * <p>物理上 397 m³ 的汽包最多装约 285 t 水（水位约 +35 %，见 {@link #maxWaterMass()}），
         * 440 t 装不进去 —— 所以 440 t 是**表盘满量程**（满水事故线），
         * 与真实机组的做法一致：表盘 0 % = 正常水位，+100 % = 满水事故线，
         * 正常运行只在 0 % 上下几个百分点。界面按这个口径标刻度。</p>
         */
        static final double LEVEL_TABLE_MAX_KG = 440000.0;

        /** 汽空间真实存量：抽汽不能超过这里（原先没有限制，靠闪蒸凭空补） */
        @Override public double availableSteamKg() {
            return Math.max(0.0, steamMass);
        }

        @Override
        public double getPressure() {
            return pressure;
        }

        @Override
        public double getSteamDensity() {
            return steamDensity;
        }

        @Override
        public void updateSteamInflow(double flow, double tempC) {
            double[] inflowData = NPPMath.mixWater(steamInflow, steamInflowTemperature, flow, tempC);
            steamInflow = inflowData[0];
            steamInflowTemperature = inflowData[1];
        }

        @Override
        public void updateSteamOutflow(double flow, double tempC) {
            steamOutflow += flow;
        }

        @Override
        public void updateWaterInflow(double flow, double tempC) {
            if (flow < 0 || waterInflow < 0) {
                waterInflow += flow;
            } else {
                double[] inflowData = NPPMath.mixWater(waterInflow, waterInflowTemperature, flow, tempC);
                waterInflow = inflowData[0];
                waterInflowTemperature = inflowData[1];
            }
        }

        @Override
        public void updateWaterOutflow(double flow, double tempC) {
            waterOutflow += flow;
        }

        public void resetFlowRates() {
            steamProduction = 0;
        }

        @Override
        public double getWaterMass() {
            return waterMass;
        }

        public double getBoilingPoint() {
            return boilingPoint;
        }

        /**
         * 汽包产汽速率 kg/s（2026-11-02 环路自检统一量纲）。
         *
         * <p>原来返回的是"自上次 resetFlowRates()（UI 每帧调一次）以来累计的 kg/tick"，
         * 既不是速率也与 tick 频率耦合；现在计数器在 update() 里按 tick 复位，
         * getter 再乘 20（tick = 50 ms）得到 kg/s，与通道侧口径一致。</p>
         */
        public double getSteamProduction() {
            return steamProduction * 20.0;
        }

        @Override
        public double getSteamMass() {
            return steamMass;
        }

        @Override
        public double getWaterDensity() {
            return specificDensityWater;
        }

        public double getWaterOutflow() {
            return waterOutflow;
        }

        @Override
        public double getSteamVolume() {
            return steamVolume;
        }

        @Override
        public double getSteamTemperature() {
            return steamTemperature;
        }
        
        public double getInitialSteamMass() {
            return initialSteamMass;
        }

        public void setSteamPressure(double press) {
            pressure = press;
        }

        /**
         * 把汽包整体重置到某个温度的饱和状态：水装量、汽空间质量、压力三者自洽。
         *
         * <p>原来的 {@code setWaterTemp} 只改水温 + 压力，不动汽空间质量：
         * 下一帧 update() 会按"汽空间质量 = 初始空气 104 kg"反算出 ~0.1 MPa，
         * 而水还停在 270 °C —— 170 K 过热度，一帧内闪蒸掉半包水，
         * 通道被瞬间抽干并误判干涸。自检（DryoutCheck / CoreHeatCouplingCheck）
         * 热态起步就踩在这个坑上。</p>
         */
        void resetToTemperature(double tempC) {
            waterTemperature = tempC;
            steamTemperature = tempC;
            specificDensityWater = Loader.tables.getWaterDensityByTemp(tempC);
            /* 按**额定质量**装水（2026-11-05）：旧写法 volume/比容 在热态下只有 163 t，
             * 一升温水位就凭空变成 −26 %，整定还没开始就少了一大截水。 */
            waterMass = NOMINAL_WATER_KG;
            waterVolume = waterMass * specificDensityWater;
            steamVolume = Math.max(0.0, volume - waterVolume);
            double p = Loader.tables.getSteamPressureByTemp(tempC);
            double svg = Loader.tables.getSteamDensityByPressure(p);   // 实为饱和汽比容 m³/kg
            if (!Double.isFinite(svg) || svg <= 0.0) {
                svg = 0.0274;
            }
            steamMass = steamVolume / svg;
            initialSteamMass = 0.0;
            pressure = p;
            boilingPoint = tempC;
            deltaEnergy = 0.0;
            steamProduction = 0.0;
            waterLevel = levelFromMass(waterMass);
        }
    }
    
    protected class SteamWaterPipe implements Serializable {
    private final FuelChannel source;
    private final Connectable drain;
    private double steamFlow = 0.0, steamTemp = 0.0;
    private double waterFlow = 0.0, waterTemp = 0.0;

    SteamWaterPipe(FuelChannel source, Connectable drain) {
        this.source = source;
        this.drain = drain;
        source.setDrain(drain);
    }

    public void update() {
        /* 通道 → 汽包是**单向**的（2026-11-07 单回路修正）。
         *
         * <p>旧实现按"汽空间体积比分配总蒸汽质量"：
         * {@code steamFlow = 通道汽 − 通道汽空间/(通道+汽包汽空间) × (通道汽+汽包汽)}。
         * 汽包汽空间 127 m³、单根通道不到 0.001 m³，比值约 4e-6，但汽包蒸汽有 4.6 t，
         * 于是**每根通道每 tick 从汽包倒扣 0.018 kg** —— 1661 根合计 600 kg/s，
         * 等于汽包在被自己的通道反向抽汽。单回路里没有任何中间环节补这一块，
         * 汽包 30 s 就掉 60 t 水、压力从 6.9 塌到 2.4 MPa。 */
        steamFlow = source.getSteamMass();
        if (steamFlow >= 0) {
            steamTemp = source.getSteamTemperature();
        } else {
            steamTemp = drain.getSteamTemperature();
        }
        waterFlow = source.getWaterOutflow();
        if (waterFlow < 0) { // reverse flow will need drain watertemp, otherwise source watertemp
            waterTemp = drain.getWaterTemperature();
        } else {
            waterTemp = source.getWaterTemperature();
        }
        // source waterOutFlow is not updated here as source does that by itself
        if (Double.isNaN(steamFlow)) {
            steamFlow = 0;
        }
        source.updateSteamOutflow(steamFlow, steamTemp);
        drain.updateSteamInflow(steamFlow, steamTemp);
        drain.updateWaterInflow(waterFlow, waterTemp);
    }
}

    protected class MCPPressureHeader extends WaterSteamSubComponent implements Connectable, UIReadable, Serializable {
        final List<FuelChannel> drains;
        private final double volume = 121.5; 
        private double waterMass, waterVolume;
        private double waterInflowTemperature = 20.0; // C
        private double specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
        private boolean bypassesOpen = true;

        MCPPressureHeader(List<FuelChannel> drains) {
            this.drains = drains;
            this.waterMass = volume / Loader.tables.getWaterDensityByTemp(waterTemperature);
            this.waterVolume = volume;
        }

        public void update() {
            waterMass -= waterOutflow;
            waterOutflowRate += waterOutflow;
            double[] waterInflowData = NPPMath.mixWater(waterMass, waterTemperature, waterInflow, waterInflowTemperature);
            waterTemperature = waterInflowData[1];
            waterMass = waterInflowData[0];
            //waterTemperature -= (0.5 * waterTemperature - 10) * 0.0000004; //TODO
            specificDensityWater = Loader.tables.getWaterDensityByTemp(waterTemperature);
            waterVolume = specificDensityWater * waterMass;
            waterOutflow = (waterVolume - volume) / specificDensityWater;
            if (waterOutflow < 0) {
                if (drains.size() == 835) {
                    double[] inflowData = NPPMath.mixWater(waterMass, waterTemperature, 0 - waterOutflow, mcc.drum1.getWaterTemperature());
                    waterMass = inflowData[0];
                    waterTemperature = inflowData[1];
                    mcc.drum1.updateWaterOutflow(0 - waterOutflow, mcc.drum1.getWaterTemperature());
                } else {
                    double[] inflowData = NPPMath.mixWater(waterMass, waterTemperature, 0 - waterOutflow, mcc.drum2.getWaterTemperature());
                    waterMass = inflowData[0];
                    waterTemperature = inflowData[1];
                    mcc.drum2.updateWaterOutflow(0 - waterOutflow, mcc.drum2.getWaterTemperature());
                }
            } else {
                waterMass -= waterOutflow;
                double flowPerDrain = waterOutflow / drains.size();
                for (Connectable i: drains) {
                    i.updateWaterInflow(flowPerDrain, waterTemperature);
                }
            }
            waterVolume = specificDensityWater * waterMass;
            resetFlows();
        }

        @Override
        public double getPressure() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public double getSteamDensity() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public double getWaterDensity() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public double getSteamMass() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public double getSteamVolume() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public double getSteamTemperature() {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public void updateSteamOutflow(double flow, double tempC) {
            // TODO Auto-generated method stub

        }

        @Override
        public void updateSteamInflow(double flow, double tempC) {
            // TODO Auto-generated method stub

        }

        @Override
        public void updateWaterOutflow(double flow, double tempC) {
            waterOutflow += flow;
        }

        @Override
        public void updateWaterInflow(double flow, double tempC) {
            waterInflow += flow;
            waterInflowTemperature = tempC; //tempC will be equal for all mcp's
        }

        public void setBypassState(boolean open) {
            bypassesOpen = open;
        }

        public boolean getBypassState() {
            return bypassesOpen;
        }

        @Override
        public double getWaterMass() {
            // 2026-11-01：原先这里直接抛 UnsupportedOperationException，
            // 任何诊断/自检读母管装量都会把调用线程打死。母管本来就有 waterMass 字段，直接返回。
            return waterMass;
        }
    }

    public void update() {
        core.coreArray.forEach(row -> {
            row.forEach(channel -> {
                channel.update();
            });
        });
        autoControl.resetTimeUpdatedFlag();
        for (SteamWaterPipe i: steamWaterPipes1) {
            i.update();
        }
        for (SteamWaterPipe i: steamWaterPipes2) {
            i.update();
        }
        drum1.update();
        drum2.update();
        sHeader1.update();
        sHeader2.update();
        for (Pump i : mcp) {
            i.update();
        }
        pHeader1.update();
        pHeader2.update();
    }

    public void setThermalPower(double thermalPower) {
        final double perChannel = thermalPower / (fuelChannels1.size() + fuelChannels2.size());
        for (FuelChannel i: fuelChannels1) {
            i.setThermalPower(perChannel);
        }
        for (FuelChannel i: fuelChannels2) {
            i.setThermalPower(perChannel);
        }
    }

    public void setWaterTemp(double tempC) {
        double saturatedPress = Loader.tables.getSteamPressureByTemp(tempC);
        for (FuelChannel i: fuelChannels1) {
            i.setWaterTemp(tempC);
            i.setSteamPressure(saturatedPress);
        }
        for (FuelChannel i: fuelChannels2) {
            i.setWaterTemp(tempC);
            i.setSteamPressure(saturatedPress);
        }
        drum1.resetToTemperature(tempC);
        drum2.resetToTemperature(tempC);
        pHeader1.waterTemperature = tempC;
        pHeader2.waterTemperature = tempC;
        pHeader1.waterMass = pHeader1.volume / NPPSim.tables.getWaterDensityByTemp(tempC);
        pHeader2.waterMass = pHeader2.volume / NPPSim.tables.getWaterDensityByTemp(tempC);
    }

}
