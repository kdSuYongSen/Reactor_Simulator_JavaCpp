package com.darwish.nppsim;

import java.io.Serializable;

/**
 * 汽包给水「三冲量」控制（2026-11-04，单回路）。
 *
 * <p>为什么需要三冲量：RBMK 的汽包只有两百多吨水、汽空间十几吨汽，是个**
 * 小容积对象**。功率一变，产汽量和汽包压力立刻跟着变，水位却要等质量进出
 * 差累积几十秒才反映出来；等水位动了再调给水阀，早就晚了 —— 要么滞后导致
 * 水位大幅波动，要么被"虚假水位"（压力变化引起汽泡体积变化）骗着反向动作。</p>
 *
 * <p>三冲量把这个问题拆成三个信号：</p>
 * <ol>
 *   <li><b>水位（主信号）</b>：最终要维持的目标，代表汽包里到底有多少水。
 *       只有它一个时就是普通的单冲量水位控制，靠它消掉静态偏差。</li>
 *   <li><b>蒸汽流量（前馈）</b>：汽包出口蒸汽量（= 汽轮机进汽 + 安全阀排放）。
 *       它一涨，说明堆芯在产更多汽、马上要带走更多水 —— <b>不等水位下降</b>
 *       就直接把给水需求加上去，这是三冲量最关键的一路。</li>
 *   <li><b>给水流量（反馈）</b>：确认阀门动作的实际效果。阀开了不等于水就到了
 *       （母管压力、泵能力、阀门特性都会影响），用"需求 − 实测给水"去修阀位，
 *       给水才能迅速跟上蒸汽。</li>
 * </ol>
 *
 * <p>结构上是串级：水位 PI 的输出 + 蒸汽流量前馈 = <b>给水流量设定值</b>；
 * 流量环再把"设定 − 实测给水"积分成阀位。稳态时给水 = 蒸汽、水位回到设定值，
 * 积分项承担全部开度，不留静态偏差。</p>
 */
class FeedwaterThreeElement implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 水位被控对象（汽包，或二回路时是换热器管侧） */
    private final Connectable levelTarget;
    /** 蒸汽流量取样的对象（汽包：它的出汽量就是当前蒸汽需求） */
    private final MCC.SeparatorDrum steamSource;
    /** 主给水阀（每台汽包 3 只） */
    private final WaterValve[] mainValves;
    /** 辅助给水阀（每台汽包 1 只），主阀不够时接力 */
    private final WaterValve[] auxValves;

    /** 水位设定 %（0 = 额定 220 t） */
    double setpoint = 0.0;

    /* ---- 主调节器（水位 → 给水需求 kg/s）---- */
    /** 比例增益：水位偏低 1 % → 多要 60 kg/s 给水 */
    double kpLevel = 60.0;
    /** 积分增益：水位偏低 1 % 持续 1 s → 多要 12 kg/s */
    double kiLevel = 12.0;
    /** 积分上限 kg/s（防止长期偏差把需求推到天上） */
    private static final double INTEGRAL_MAX = 900.0;

    /* ---- 副调节器（给水流量 → 阀位）---- */
    /** 阀位速度增益：给水比需求少 100 kg/s 时，每秒开 0.10 */
    double kFlow = 1.0e-3;
    /** 阀位累计（0…2：0…1 给主阀，1…2 溢出给辅阀） */
    private double valvePos = 0.0;

    private double integral = 0.0;
    private boolean enabled = false;

    /* ---- 观测值（界面 / 自检用） ---- */
    private double lastLevel, lastSteam, lastFeed, lastDemand;

    FeedwaterThreeElement(Connectable levelTarget, MCC.SeparatorDrum steamSource,
                          WaterValve[] mainValves, WaterValve[] auxValves) {
        this.levelTarget = levelTarget;
        this.steamSource = steamSource;
        this.mainValves = mainValves;
        this.auxValves = auxValves;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** 手动给一个初始阀位（装载标准工况时用：直接落到平衡点，省掉几十秒的爬升） */
    void setValvePosition(double pos) {
        valvePos = Math.max(0.0, Math.min(2.0, pos));
        applyValves();
    }

    double getValvePosition() {
        return valvePos;
    }

    double getDemand() {
        return lastDemand;
    }

    double getFeedFlow() {
        return lastFeed;
    }

    /** 每 tick 调用（dt = 0.05 s） */
    /**
     * 最小阀位（2026-11-07）：给水阀不许关到 0。
     *
     * <p>三冲量的给水设定跟着蒸汽流量走，而蒸汽流量又由换热器抽汽决定、抽汽又受
     * 二次侧给水限制 —— 一旦某一刻给水被压到 0，二次侧没水蒸发、蒸汽流量归零，
     * 设定也跟着归零，整条链自锁在"0 给水 / 0 产汽"上（实测 t=10 s 就锁死）。</p>
     *
     * <p>保底 25 %（约 850 kg/s）保证二次侧始终有水可蒸发，环路不会自锁。</p>
     */
    private static final double MIN_VALVE_POS = 0.25;

    void update(double dt) {
        if (!enabled) {
            return;
        }
        lastLevel = levelTarget.getWaterLevel();
        /* 前馈：上一 tick 汽包实际送出的蒸汽 kg/s（= 当前蒸汽需求） */
        lastSteam = steamSource.getSteamOutflowRate();
        lastFeed = measuredFeedFlow();

        double err = setpoint - lastLevel;          // 正 = 水位偏低 → 该多加
        integral += err * kiLevel * dt;
        integral = Math.max(-INTEGRAL_MAX, Math.min(INTEGRAL_MAX, integral));

        /* 给水流量设定 = 蒸汽流量（前馈）+ 水位 PI（主信号） */
        double demand = lastSteam + kpLevel * err + integral;
        demand = Math.max(0.0, Math.min(4000.0, demand));
        lastDemand = demand;

        /* 副调节器：用"设定 − 实测给水"把阀位往正确方向推（速度式，天然无静差） */
        double flowErr = demand - lastFeed;
        valvePos += kFlow * flowErr * dt * 20.0;    // ×20：dt 是 0.05 s，换成"每秒"的增益口径
        valvePos = Math.max(MIN_VALVE_POS, Math.min(2.0, valvePos));
        applyValves();
    }

    /** 实测给水流量 kg/s（主 + 辅阀） */
    private double measuredFeedFlow() {
        double sum = 0.0;
        for (WaterValve v : mainValves) {
            if (v != null) {
                sum += v.timestepFlow * 20.0;
            }
        }
        for (WaterValve v : auxValves) {
            if (v != null) {
                sum += v.timestepFlow * 20.0;
            }
        }
        return sum;
    }

    /** 阀位下发：主阀用 0…1，超过 1 的部分接力给辅助给水阀 */
    private void applyValves() {
        float mainPos = (float) Math.max(0.0, Math.min(1.0, valvePos));
        float auxPos = (float) Math.max(0.0, Math.min(1.0, valvePos - 1.0));
        for (WaterValve v : mainValves) {
            if (v != null) {
                v.setAutoState(1);
                v.setState(1);
                v.setPosition(mainPos);
            }
        }
        for (WaterValve v : auxValves) {
            if (v != null) {
                v.setAutoState(1);
                v.setState(1);
                v.setPosition(auxPos);
            }
        }
    }
}
