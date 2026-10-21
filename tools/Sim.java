import com.eren.gumustakip.TradingAlgorithm;
import com.eren.gumustakip.TradingAlgorithm.*;
import java.util.*;

public class Sim {
    interface Gen { double next(Random r, int i, double p); }

    static double[] run(String name, Gen g, long seed, int days, double spread, boolean verbose) {
        Random r = new Random(seed);
        List<Tick> ticks = new ArrayList<>();
        long t0 = 1_700_000_000_000L, step = 60_000L;
        double p = 100;
        Position pos = new Position(); pos.grams = 100; pos.avgCost = 100; pos.cash = 5000;
        double startWealth = pos.grams * (p - spread/2) + pos.cash;
        long cooldownUntil = 0; int trades = 0, bad = 0; double spentTotal=0;
        int n = days * 24 * 60;
        for (int i = 0; i < n; i++) {
            p = g.next(r, i, p);
            long now = t0 + i * step;
            double sell = p - spread / 2, buy = p + spread / 2;
            ticks.add(new Tick(now, buy, sell));
            if (ticks.size() > 480) ticks.remove(0);
            if (now < cooldownUntil) continue;
            Signal s = TradingAlgorithm.evaluate(ticks, now, pos);
            if (s.isHold()) continue;
            if (s.fraction != 0.25 && s.fraction != 0.5) bad++;
            double q = s.grams;
            if (s.action.equals("SELL")) {
                if (q > pos.grams + 1e-9 || q <= 0) { bad++; continue; }
                pos.cash += q * s.price; pos.grams -= q;
                pos.lastTradeType = "SELL";
            } else {
                if (q <= 0) continue;
                double cost = q * s.price; if (cost > pos.cash) { q = pos.cash / s.price; cost = pos.cash; }
                if (q < 0.01) continue;
                pos.avgCost = (pos.grams * pos.avgCost + cost) / (pos.grams + q);
                pos.grams += q; pos.cash -= cost; pos.lastTradeType = "BUY";
            }
            pos.lastTradePrice = s.price; pos.lastTradeTs = now; trades++;
            cooldownUntil = now + 5 * 60_000L;
        }
        double endSell = ticks.get(ticks.size()-1).sell;
        double algoWealth = pos.grams * endSell + pos.cash;
        double holdWealth = 100 * endSell + 5000;
        if (verbose) System.out.printf(Locale.US, "%-22s seed=%d  işlem=%3d  algo=%9.0f  al-tut=%9.0f  fark=%+8.0f  hatalı=%d%n",
                name, seed, trades, algoWealth, holdWealth, algoWealth - holdWealth, bad);
        return new double[]{algoWealth - holdWealth, trades, bad};
    }

    public static void main(String[] a) {
        double sp = Double.parseDouble(System.getProperty("spread","2.0")); final double V = Double.parseDouble(System.getProperty("vol","0.12"));
        Gen rw = (r, i, p) -> Math.max(60, p + r.nextGaussian() * V);
        Gen up = (r, i, p) -> Math.max(60, p + 0.004 + r.nextGaussian() * V);
        Gen down = (r, i, p) -> Math.max(60, p - 0.004 + r.nextGaussian() * V);
        // dalgalı: ~6 saatlik sinüs + gürültü
        Gen wave = (r, i, p) -> Math.max(60, 100 + 6 * Math.sin(i / 360.0 * Math.PI) + r.nextGaussian() * 0.35 + 0 * p);
        // trend rejimleri: 3-5 saat süren yönlü hareketler
        final double[] drift = {0}; final int[] left = {0};
        Gen regime = (r, i, p) -> { if (left[0] <= 0) { left[0] = 120 + r.nextInt(240); drift[0] = (r.nextDouble() - 0.5) * 0.02; } left[0]--; return Math.max(60, p + drift[0] + r.nextGaussian() * V); };

        String[] names = {"rastgele(kenar yok)", "yükseliş", "düşüş", "dalga", "trend rejimleri"};
        Gen[] gens = {rw, up, down, wave, regime};
        for (int k = 0; k < gens.length; k++) {
            double sum = 0, tr = 0, bad = 0; int N = Integer.getInteger("N",8);
            for (int s = 1; s <= N; s++) { drift[0]=0; left[0]=0; double[] x = run(names[k], gens[k], s, Integer.getInteger("days",5), sp, false); sum += x[0]; tr += x[1]; bad += x[2]; }
            System.out.printf(Locale.US, "%-22s 10 gün x%d tohum: fark=%+8.0f TL  ort. işlem=%5.1f  hatalı oran/miktar=%.0f%n", names[k], N, sum / N, tr / N, bad);
        }
    }
}
