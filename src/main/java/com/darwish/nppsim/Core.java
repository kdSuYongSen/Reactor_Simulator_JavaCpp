package com.darwish.nppsim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 反应堆堆芯。
 *
 * 物理由 {@link ReactorKinetics}（8 轴向节点点堆动力学，6 组缓发中子 + 氙毒 +
 * 多普勒/慢化剂/空泡反馈 + 自适应求解器）统一计算；本类负责：
 *  1) 构建 55×56 堆芯通道阵列（coreMap.csv，1661 根燃料通道 + 控制棒/探测器）；
 *  2) 每 tick 采集电厂状态（棒位、压力、流量、入口焓）喂给引擎；
 *  3) 把引擎结果（总功率/反应性/中子数/周期，及每通道功率与碘氙）分配出去——
 *     燃料通道继续按原有方式沸腾产汽送汽鼓，UI（堆芯图/MTK）数据接口不变。
 */
public class Core extends Component {
        final ArrayList<ArrayList<Channel>> coreArray = new ArrayList<>();
        private double neutronCount, previousNeutronCount, reactivity, period, thermalPower;
        private double fcNeutronCount;
        /** C++ 计算核心委托层（native 句柄不可序列化 → transient；读档后 update() 重建） */
        private transient ReactorKinetics kinetics = new ReactorKinetics();
        /** 核心离线冷却系统（PCS 水箱→离线泵 A/B→堆芯→排放），随存档序列化；
         *  旧档反序列化后为 null，update() 里自动补建。 */
        OfflineCoolingSystem offlineCooling = new OfflineCoolingSystem();
        /** 额定一回路流量 kg/s（与内核 MDOT_NOM 一致） */
        static final double RATED_FLOW_KGS = 12000.0;
        /** 标准工况装载后的流量保护期：45 s（900 tick），足够循环流量建立 */
        static final int RATED_FLOW_GRACE_TICKS = 900;
        private int fuelChannelCount = 0;
        /**
         * 装载标准初始工况后的"额定流量保护期"（tick 数，2026-11-02）。
         *
         * <p>一回路循环流量在模型里是靠各等容节点的容积偏差逐拍传递建立的：
         * 主泵起转 → 压力母管 → 1661 根通道 → 汽包 → 吸入母管，实测要约 20~30 s 才到额定。
         * 而内核一旦拿到"接近 0 的流量"就会立刻把堆芯吹空/空泡化，功率从 4800 MW 掉到几百 MW，
         * 再也回不来。装载标准工况时先给内核一段额定流量，等实测流量真的建立起来再交给它。</p>
         */
        private int icRatedFlowTicks = 0;
        /**
         * 标准工况装载后"等着把自动 AZ 投回去"（2026-11-02）。
         *
         * <p>不能一到时间就投：整定后的头两分钟里堆芯会先冲到 6 GW 左右，
         * 再被 АР 拉回 4.8 GW。如果按固定 45 s 恢复保护，AZ-3（5200 MW）立刻动作，
         * SCRAM 全棒插到底，功率掉到 500 MW —— 实测就是这样。
         * 所以等到**功率真的回到 AZ-3 定值以下**再投保护。</p>
         */
        private boolean icAzRestorePending = false;
        /** 功率降到这个值以下才把自动 AZ 投回去（AZ-3 定值 5200 MW，留 100 MW 余量） */
        /**
         * 装载标准工况后恢复"自动 AZ"的功率阈值 MW（2026-11-04 由 5100 降到 4700）。
         *
         * <p>5100 意味着功率一到 5100 以下就恢复自动保护 —— 而单回路整定后的头十几秒
         * 功率正好在 4800 上下冲，AZ 立刻恢复，紧接着的瞬态冲高就按 AZ-1K「功率 > 120 %」
         * 把整堆插掉（实测装载后 10 s 内就跳到 400 MW）。改到 4700（低于额定的 98 %）
         * 并要求连续低于阈值 2 s，瞬态段就不会误投。</p>
         */
        static final double AZ_RESTORE_POWER_MW = 4700.0;
        /** 恢复到阈值以下需要持续的 tick 数（2 s） */
        private int azRestoreTicks = 0;

        public Core() {
            ArrayList<List<String>> coreData = getCoreData();
            for (int y = 0; y < 56; y++) {
                coreArray.add(new ArrayList<>());
                for (int x = 0; x < 56; x++) {
                    Channel channel = initializeChannelByIdentifier(coreData.get(y).get(x));
                    channel.setPositionString(NPPMath.formatChannelNumber(x, y));
                    coreArray.get(y).add(channel);
                }
            }
            computeRadialShape();
        }

        /** 径向功率形状：中心高、边缘低的 cos² 形，归一到均值 1（保留堆芯图的空间差异） */
        private void computeRadialShape() {
            final double cx = 27.5, cy = 27.5, rMax = 28.0;
            double sum = 0;
            int count = 0;
            for (int y = 0; y < coreArray.size(); y++) {
                ArrayList<Channel> row = coreArray.get(y);
                for (int x = 0; x < row.size(); x++) {
                    Channel channel = row.get(x);
                    // 堆芯图行号从上往下（y=0 是顶部），取到笛卡尔距离即可（对称）
                    double dx = y - cy;
                    double dy = x - cy;
                    double rNorm = Math.min(1.0, Math.sqrt(dx * dx + dy * dy) / rMax);
                    double shape = 1.45 - 0.9 * rNorm * rNorm;
                    if (channel instanceof FuelChannel) {
                        fuelChannelCount++;
                        ((FuelChannel) channel).setRadialShape(shape);
                        sum += shape;
                        count++;
                    } else if (channel instanceof CPSChannel) {
                        // CPS 通道也要给自己位置上的形状：MTK 的绿点靠它显示通量分布
                        ((CPSChannel) channel).setRadialShape(shape);
                    }
                }
            }
            double mean = count > 0 ? sum / count : 1.0;
            for (ArrayList<Channel> row : coreArray) {
                for (Channel channel : row) {
                    if (channel instanceof FuelChannel) {
                        FuelChannel fc = (FuelChannel) channel;
                        fc.setRadialShape(fc.getRadialShape() / mean);
                    } else if (channel instanceof CPSChannel) {
                        CPSChannel cc = (CPSChannel) channel;
                        cc.setRadialShape(cc.getRadialShape() / mean);
                    }
                }
            }
            kinetics.fuelChannelCount = Math.max(1, fuelChannelCount);
        }

        /**
         * 把当前 211 根棒的棒位汇总进内核（不推进物理）。
         *
         * <p>与 {@link #update()} 里那段求和同口径：Java 侧 position 1=全插、0=全提，
         * 内核要的是 0=全插、1=全提（pushInputs 里取 1-x）。标准初始工况要在
         * **复位内核之前**先把棒位对齐，否则复位后第一帧会把"全插"推给内核，
         * 下一帧再推 0.25 等于"瞬间提棒"注入一大笔正反应性。</p>
         */
        void syncRodInsertion() {
            double rodSum = 0;
            int rodCount = 0;
            for (ArrayList<Channel> row : coreArray) {
                for (Channel channel : row) {
                    if (channel instanceof ControlRodChannel) {
                        rodSum += ((ControlRodChannel) channel).getPosition();
                        rodCount++;
                    }
                }
            }
            if (rodCount > 0 && kinetics != null) {
                kinetics.rodInsertion = rodSum / rodCount;
            }
        }

        /**
         * 复位到额定平衡态（2026-11-02，标准初始工况用）。
         * 调用前请先把各棒位置设好——本方法会把它们汇总进内核再复位。
         */
        boolean resetToNominal() {
            if (kinetics == null) {
                kinetics = new ReactorKinetics();
            }
            syncRodInsertion();
            boolean ok = kinetics.resetToNominal();
            icRatedFlowTicks = RATED_FLOW_GRACE_TICKS;
            // 立刻同步显示量，界面不必等下一帧
            thermalPower = kinetics.totalPowerMW;
            neutronCount = kinetics.neutronTotal;
            reactivity = kinetics.reactivityAbs;
            period = kinetics.periodSeconds;
            fcNeutronCount = kinetics.neutronTotal;
            return ok;
        }

        public void update() {
            // ---- 0) 离线冷却：泵/阀推进并写入引擎（旧档兼容：字段缺失时补建）----
            if (offlineCooling == null) {
                offlineCooling = new OfflineCoolingSystem();
            }
            if (kinetics == null) {
                kinetics = new ReactorKinetics();   // 读档：native 句柄不序列化，重建
            }
            offlineCooling.update(0.05);
            // 离线冷却外环路：除盐水箱 → 离线泵 A/B → 换热器 A/B → 河。河水记录带走的热量。
            if (NPPSim.pcs != null && NPPSim.pcs.river != null && offlineCooling.getTotalFlowKgs() > 0.1) {
                NPPSim.pcs.river.updateWaterInflow(offlineCooling.getTotalFlowKgs() * 0.05,
                        offlineCooling.getOutletTempC());
            }
            kinetics.mdotOffline = offlineCooling.getTotalFlowKgs();
            kinetics.hOfflineIn = Loader.tables.getWaterEnthalpyByTemperature(OfflineCoolingSystem.WATER_TEMP_C);

            // ---- 1) 采集电厂状态（上一 tick 的棒位/压力/流量/入口温度）----
            double rodSum = 0;
            int rodCount = 0;
            double flowSum = 0, inflowTempFlowSum = 0;
            int inflowCount = 0;
            int fuelSeen = 0, dryCount = 0;
            double pressSum = 0;
            int pressCount = 0;
            for (ArrayList<Channel> row : coreArray) {
                for (Channel channel : row) {
                    if (channel instanceof ControlRodChannel) {
                        rodSum += ((ControlRodChannel) channel).getPosition();
                        rodCount++;
                    } else if (channel instanceof FuelChannel) {
                        FuelChannel fc = (FuelChannel) channel;
                        pressSum += fc.getPressure();
                        pressCount++;
                        fuelSeen++;
                        if (fc.isDryout()) dryCount++;
                        double r = fc.getWaterInflowRate();
                        if (r > 0) {
                            flowSum += r;
                            inflowTempFlowSum += r * fc.getWaterInflowTemperature();   // 入口温度按通道流量加权
                            inflowCount++;
                        }
                    }
                }
            }
            /* 干涸通道占比 → 内核平均换热系数。取上一 tick 的通道状态（此刻通道还没更新），
             * 50 ms 的滞后对传热恶化这种秒级过程没有影响。 */
            /* 保护期内不要报干涸（2026-11-02）：装载标准工况后的头 45 s 里一回路流量还在建立，
             * 通道侧会瞬时"干涸"。把这个假信号推给内核会让它按传热恶化处理，
             * 功率从 4800 MW 掉到几百 MW 且每次都不一样。 */
            kinetics.dryoutFraction = icRatedFlowTicks > 0
                    ? 0.0
                    : (fuelSeen > 0 ? (double) dryCount / fuelSeen : 0.0);
            kinetics.rodInsertion = rodCount > 0 ? rodSum / rodCount : kinetics.rodInsertion;
            kinetics.pressureMPa = pressCount > 0 ? pressSum / pressCount : kinetics.pressureMPa;
            // 仅主回路流量（MCP）；离线冷却经换热器/河水单独通道进引擎，不并入（P0：避免重复计入）。
            // 读档后的第一个 tick 通道流量可能尚未重新建立（为 0），此时保留存档恢复的输入，
            // 不能把刚恢复的堆芯按 0 流量再推一步。
            if (icRatedFlowTicks > 0) {
                /* 保护期：实测流量还没建立，先按额定喂内核；实测值更大时以实测为准。 */
                icRatedFlowTicks--;
                if (icRatedFlowTicks == 0) {
                    icAzRestorePending = true;
                }
                kinetics.mdotTotal = Math.max(flowSum, RATED_FLOW_KGS * 0.95);
            } else if (flowSum > 1.0) {
                kinetics.mdotTotal = flowSum;
                if (inflowCount > 0) {
                    double tIn = inflowTempFlowSum / flowSum;
                    // C++ 的焓参考点是 h(270°C)=0；蒸汽表给的是绝对焓（kJ/kg），
                    // 因此这里减去 270°C 的基准，避免把 ~1187 kJ/kg 的绝对值直接注入。
                    double hAbs = Loader.tables.getWaterEnthalpyByTemperature(tIn);
                    double hRef = Loader.tables.getWaterEnthalpyByTemperature(270.0);
                    kinetics.hInHeader = hAbs - hRef;
                }
            }

            // ---- 2) 物理引擎推进（内部自适应子步）----
            kinetics.update(0.05);   // 仿真帧长 50 ms

            // ---- 3) 结果分配：功率/碘/氙 → 每根燃料通道（按径向形状）----
            /* 自检注入 coreHeatOverrideMW ≥ 0 时，用它替代堆芯功率。
             * 2026-11-01 前这个注入是直接写进汽包热源的（与通道加热重复计入）；
             * 现在热量只能走"堆芯 → 通道 → 汽包"这一条路，所以注入点也必须挪到通道侧。 */
            double powerMW = kinetics.totalPowerMW;
            if (NPPSim.coreHeatOverrideMW >= 0.0) {
                powerMW = NPPSim.coreHeatOverrideMW;
            }
            if (!Double.isFinite(powerMW) || powerMW < 0.0) {
                powerMW = 0.0;
            }
            /* 标准工况装载期间的自动 AZ 旁路：等功率真回到 AZ-3 定值（5200 MW）以下再投回去。
             * 否则会在整定后的功率冲高段被 AZ-3 直接 SCRAM（实测掉到 500 MW）。 */
            if (icAzRestorePending && NPPSim.autoControl != null && powerMW > 0.0
                    && powerMW < AZ_RESTORE_POWER_MW) {
                if (++azRestoreTicks >= 40) {
                    NPPSim.autoControl.azAutoEnabled = true;
                    icAzRestorePending = false;
                    azRestoreTicks = 0;
                }
            } else {
                azRestoreTicks = 0;
            }
            double perChannelBase = powerMW / Math.max(1, fuelChannelCount);
            double chNominalMW = 4800.0 / Math.max(1, fuelChannelCount);   // 额定通道功率（相对通量归一基准）
            final double coreRelativePower = powerMW / 4800.0;             // 堆芯相对功率（1.0 = 额定）
            double tempSum = 0;
            int tempCount = 0;
            for (ArrayList<Channel> row : coreArray) {
                for (Channel channel : row) {
                    if (channel instanceof FuelChannel) {
                        FuelChannel fc = (FuelChannel) channel;
                        double shape = fc.getRadialShape();
                        double p = perChannelBase * shape;
                        fc.setKineticsPower(p,
                                kinetics.i135AvgForUI * shape,
                                kinetics.xe135AvgForUI * shape);
                        // 通道相对通量（额定通道=1.0）：供 AR 棒控按局部功率挑选通道 + 表格 Count 行
                        double flux = p / chNominalMW;
                        fc.setNeutronPopulation(flux, flux);
                        tempSum += fc.getWaterTemperature() * flux;
                        tempCount += flux;   // 相对通量加权（等效流量加权，热通道不被稀释）
                    } else if (channel instanceof CPSChannel) {
                        /* 控制棒通道不产热，但堆芯图上要显示它所在位置的通量。
                         * 原始 jar 靠 56×56 元胞自动机的邻域中子扩散给出；点堆内核没有空间中子学，
                         * 这里用同一套径向形状 × 堆芯相对功率补上 —— 否则 CPS 恒为初值 1.0，
                         * 223 个绿点整层恒暗（mean=1.0，永远达不到 1+阈值）。 */
                        CPSChannel cc = (CPSChannel) channel;
                        double flux = coreRelativePower * cc.getRadialShape();
                        cc.setNeutronPopulation(flux, flux);
                    }
                }
            }
            thermalPower = powerMW;
            reactivity = kinetics.reactivityAbs;
            previousNeutronCount = neutronCount;   // 上一 tick 值（旧实现同 tick 双赋，差分恒 0）
            neutronCount = kinetics.neutronTotal;
            period = kinetics.periodSeconds;
            fcNeutronCount = kinetics.neutronTotal;
            // 离线冷却面板温度 = 电厂侧真实通道水温（堆芯出口）。
            // 不用 C++ 引擎内部 Tcool——其停堆初值保留满功率热态剖面（280-300°C），
            // 与电厂侧 20°C 冷水矛盾（曾显示 300.1°C）。
            offlineCooling.outletTempC = tempCount > 0 ? tempSum / tempCount : 20.0;
        }

        /**
         *
         * @param x
         * @param y
         * @param c relative position
         * @param out 复用的输出数组，写入 {x, y}，避免每次调用分配数组
         */
        private Channel initializeChannelByIdentifier(String channelIdentifier) {
            switch (channelIdentifier) {
                case "\\": //PDMS-A Channel
                    return new PDMSChannel();
                case "^": //fission chamber
                    return new FissionChamber();
                case "_": //MCR
                    return new MCRChannel();
                case "E": //LEP
                    return new LEPChannel();
                case "o": //MCRM
                    return new MCRMChannel();
                case "L": //LAC
                    return new LACChannel();
                case "A": //AR
                    return new ACRChannel();
                case "x": //SAR
                    return new SARChannel();
                case "R": //axial control sar
                    return new SACRChannel();
                case "%": //FASR
                    return new FASRChannel();
                case "#": //fuel channel
                    return new FuelChannel();
                case "*": //reflector cooling channel
                    return new ReflectorCoolingChannel();
                case ".": //reflector
                    return new ReflectorChannel();
                case "v": //void
                    return new VoidChannel();
                default:
                    return null;
            }

        }

        private ArrayList<List<String>> getCoreData() {
            ArrayList<List<String>> result = new ArrayList<>();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader((getClass().getResourceAsStream("/res/coreMap.csv"))));
                ArrayList<String> dataStringArray = new ArrayList<>();
                String line;
                while ((line=reader.readLine())!=null) {
                    dataStringArray.add(line);
                }
                for (int i = 1; i < dataStringArray.size(); i++) {
                    String[] dataArray = dataStringArray.get(i).split(",");
                    ArrayList<String> data = new ArrayList<>(Arrays.asList(dataArray));
                    data.remove(data.size() - 1);
                    result.add(data);
                }
                if (result.size() != 56) {
                    throw new Exception("CorruptedCoreDataException");
                }
                for (List<String> i: result) {
                    if (i.size() != 56) {
                        throw new Exception("CorruptedCoreDataException");
                    }
                }
            } catch (Exception e) {
                // 堆芯图数据损坏时显式失败，避免构造出残缺 coreArray 后到处 NPE
                throw new RuntimeException("coreMap.csv 加载失败", e);
            }
            return result;
        }

        // ------------------------------------------------------------------
        // 存档：C++ 内核完整状态（显示字段本身已随 Core 一起序列化）
        // ------------------------------------------------------------------
        /** 导出 C++ 内核完整状态；安全模式/旧 DLL 返回 null。 */
        double[] captureNativeState() {
            return kinetics == null ? null : kinetics.captureNativeState();
        }

        /** 用存档状态重建/覆盖内核；成功返回 true。 */
        boolean restoreNativeState(double[] state) {
            if (state == null) {
                return false;
            }
            if (kinetics == null) {
                kinetics = new ReactorKinetics();   // 读档：transient 句柄重建
            }
            return kinetics.restoreNativeState(state);
        }

        /** 保存内核的 Java 侧输入（棒位/流量/压力/入口焓/离线流量）。 */
        SaveGame.CoreInputs captureInputs() {
            if (kinetics == null) {
                return null;
            }
            SaveGame.CoreInputs s = new SaveGame.CoreInputs();
            // 优先用 native 内核里实际生效的值：Java 侧 mdotTotal 可能恰好是
            // UI resetFlowRates() 后的 0，直接存档会把恢复后的堆芯按 0 流量再推。
            s.rodInsertion = kinetics.actualRodInsertion();
            s.mdotTotal = kinetics.actualFlowKgS();
            s.pressureMPa = kinetics.pressureMPa;
            s.hInHeader = kinetics.hInHeader;
            s.mdotOffline = kinetics.mdotOffline;
            s.hOfflineIn = kinetics.hOfflineIn;
            s.fuelChannelCount = kinetics.fuelChannelCount;
            return s;
        }

        /** 恢复内核的 Java 侧输入；必须在 restoreNativeState 之后调用。 */
        void restoreInputs(SaveGame.CoreInputs s) {
            if (s == null || kinetics == null) {
                return;
            }
            kinetics.rodInsertion = s.rodInsertion;
            kinetics.pressureMPa = s.pressureMPa;
            kinetics.mdotTotal = s.mdotTotal;
            kinetics.hInHeader = s.hInHeader;
            kinetics.mdotOffline = s.mdotOffline;
            kinetics.hOfflineIn = s.hOfflineIn;
            if (s.fuelChannelCount > 0) {
                kinetics.fuelChannelCount = s.fuelChannelCount;
            }
        }

        public double getThermalPower() {
            return thermalPower;
        }

        public double getReactivity() {
            return reactivity;
        }

        public double getNeutronCount() {
            return previousNeutronCount;
        }

        public double getPeriod() {
            return period;
        }

        /** 诊断：当前棒平均插入深度（0=全抽出，1=全插入） */
        public double getRodInsertion() {
            return kinetics.rodInsertion;
        }

        /** 诊断：氙毒（pcm，负值） */
        public double getXenonWorthPcm() {
            return kinetics.xenonWorthPcm;
        }

        /** 核心离线冷却系统（面板控制/读数） */
        public OfflineCoolingSystem getOfflineCooling() {
            return offlineCooling;
        }
    }
