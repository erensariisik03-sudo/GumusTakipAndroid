package com.eren.gumustakip;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Deterministic heuristic. It does not place orders and cannot guarantee profit. */
public final class TradingAlgorithm {
    private TradingAlgorithm() {}
    public static final class Signal {
        public final String action, reason;
        public final double fraction;
        Signal(String a,double f,String r){action=a;fraction=f;reason=r;}
        public String message(double grams,double sell,double buy){
            if(action.equals("SELL")) return String.format(Locale.US,"SATIŞI DEĞERLENDİR\nÖneri: %.0f%% (%.3f g)\nSatış fiyatı: %.3f TL/g\n%s\nİşlem otomatik yapılmaz.",fraction*100,grams*fraction,sell,reason);
            if(action.equals("BUY")) return String.format(Locale.US,"ALIMI DEĞERLENDİR\nÖnerilen üst bütçe: portföy değerinin %%25'i\nAlış fiyatı: %.3f TL/g\n%s\nGerçek nakit bakiyesi işlem öncesi kontrol edilmeli.",buy,reason);
            return "BEKLE\n"+reason;
        }
    }
    public static Signal evaluate(Context c,double sell,double buy,double grams){
        SharedPreferences prefs=c.getSharedPreferences("gumus",Context.MODE_PRIVATE); JSONArray rows;
        try{rows=new JSONArray(prefs.getString("live_rows","[]"));}catch(Exception e){rows=new JSONArray();}
        if(rows.length()<5)return new Signal("HOLD",0,"En az 5 fiyat örneği oluşana kadar sinyal üretilmiyor.");
        long now=System.currentTimeMillis(); int timed=0;
        for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o!=null&&o.optLong("timestamp",0)>0)timed++;}
        if(timed<5)return new Signal("HOLD",0,"Zaman aralıklarını değerlendirmek için yeterli geçmiş veri biriktiriliyor.");
        double p5=priceAtMinutes(rows,now,5,sell),p15=priceAtMinutes(rows,now,15,sell),p30=priceAtMinutes(rows,now,30,sell),p60=priceAtMinutes(rows,now,60,sell);
        double c5=sell-p5,c15=sell-p15,c30=sell-p30,c60=sell-p60;
        String trend=String.format(Locale.US,"5 dk: %+.2f TL; 15 dk: %+.2f TL; 30 dk: %+.2f TL; 60 dk: %+.2f TL.",c5,c15,c30,c60);
        if(c60<=-1.0&&c15<0&&c5<0){double fraction=(c60<=-3.0&&c15<=-1.0)?0.50:0.25;if(grams*fraction<0.001)return new Signal("HOLD",0,"Düşüş var ancak satılabilir miktar yok. "+trend);return new Signal("SELL",fraction,trend+" Düşüş kısa ve orta aralıklarla teyit edildi.");}
        if(c60>=1.0&&c15>0&&c5>0)return new Signal("BUY",0,trend+" Yükseliş kısa ve orta aralıklarla teyit edildi. Alım üst sınırı portföy değerinin %25'i.");
        return new Signal("HOLD",0,"Tutarlı alım/satım sinyali yok. "+trend);
    }
    private static double priceAtMinutes(JSONArray a,long now,int minutes,double fallback){long target=now-minutes*60_000L;JSONObject best=null;long bestTs=0;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;long ts=o.optLong("timestamp",0);if(ts>0&&ts<=target&&ts>bestTs){bestTs=ts;best=o;}}return best==null?fallback:best.optDouble("sell",fallback);}
}
