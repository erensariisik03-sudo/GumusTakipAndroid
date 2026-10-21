import com.eren.gumustakip.TradingAlgorithm;
import com.eren.gumustakip.TradingAlgorithm.*;
import java.util.*;
public class Demo {
    public static void main(String[] a) {
        Random r = new Random(7);
        List<Tick> t = new ArrayList<>();
        long t0 = 1_700_000_000_000L; double p = 100, sp = 2;
        Position pos = new Position(); pos.grams = 100; pos.avgCost = 99; pos.cash = 5000;
        String last = "";
        for (int i = 0; i < 360; i++) {
            double drift = i < 120 ? 0 : i < 200 ? 0.08 : i < 230 ? 0.0 : -0.09;
            p += drift + r.nextGaussian() * 0.08;
            long now = t0 + i * 60_000L;
            t.add(new Tick(now, p + sp / 2, p - sp / 2));
            Signal s = TradingAlgorithm.evaluate(t, now, pos);
            if (!s.isHold() && !s.action.equals(last)) {
                System.out.printf(Locale.US, "--- dk %d  fiyat(sat)=%.2f  -> %s %.0f%% ---%n%s%n%n", i, p - sp / 2, s.action, s.fraction * 100, s.message);
                last = s.action;
            }
        }
    }
}
