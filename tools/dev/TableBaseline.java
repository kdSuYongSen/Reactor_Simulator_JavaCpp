package com.darwish.nppsim;

/**
 * 蒸汽表回归基线（2026-11-11）。
 *
 * <p>在性能改造前后各跑一次，逐值比对：改造只允许更快，不允许改变数值。</p>
 */
public class TableBaseline {

    public static void main(String[] args) {
        Localization.init();
        SteamTables t = new SteamTables();

        System.out.println("## temp -> p / rho / hf / hg / hfg / sf / sg / sfg");
        for (int temp = 1; temp <= 372; temp++) {
            System.out.printf("T%03d %.10f %.10f %.10f %.10f %.10f %.10f %.10f %.10f%n",
                    temp,
                    t.getSteamPressureByTemp(temp),
                    t.getWaterDensityByTemp(temp),
                    t.getWaterEnthalpyByTemperature(temp),
                    t.getSteamEnthalpyByTemperature(temp),
                    t.getSpecificVaporEnthalpyByTemperature(temp),
                    t.getWaterEntropyByTemperature(temp),
                    t.getSteamEntropyByTemperature(temp),
                    t.getVaporizationEntropyByTemperature(temp));
        }

        System.out.println("## pressure -> T / rho");
        for (int i = 2; i < 22064; i += 7) {
            double p = i / 1000.0;           // 0.002 .. 22.063 MPa
            System.out.printf("P%.4f %.10f %.10f%n", p,
                    t.getSteamTemperatureByPressure(p), t.getSteamDensityByPressure(p));
        }
        for (double p : new double[]{0.0061, 0.53, 6.60, 6.90, 7.00, 7.40, 21.9}) {
            System.out.printf("P%.4f %.10f %.10f%n", p,
                    t.getSteamTemperatureByPressure(p), t.getSteamDensityByPressure(p));
        }

        System.out.println("## density -> p");
        for (int i = 32; i < 77875; i += 23) {
            double d = i / 1000.0;
            System.out.printf("D%.4f %.10f%n", d, t.getSteamPressureByDensity(d));
        }
        for (double d : new double[]{0.0032, 3.2, 20.0, 40.0, 77.87}) {
            System.out.printf("D%.4f %.10f%n", d, t.getSteamPressureByDensity(d));
        }

        System.out.println("## enthalpy -> T");
        for (int i = 629; i <= 20483; i += 11) {
            double h = i / 10.0;
            System.out.printf("H%.1f %.10f%n", h, t.getWaterTemperatureByEnthalpy(h));
        }
        System.out.flush();
        System.exit(0);
    }
}
