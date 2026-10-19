package com.eren.gumustakip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_REQUEST = 1001;
    private SharedPreferences prefs;
    private EditText gramInput, costInput, controlIntervalInput;
    private TextView priceView, portfolioView, updateView, statusView, aiView, liveTableView, pathView;
    private TextView scheduleView;
    private Button startButton, stopButton;

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if ("com.eren.gumustakip.UPDATE_UI".equals(intent.getAction())) {
                double sell = intent.getDoubleExtra("sell", 0), buy = intent.getDoubleExtra("buy", 0);
                double grams = intent.getDoubleExtra("grams", 0), cost = intent.getDoubleExtra("cost", 0);
                showLive(buy, sell, grams, cost, intent.getStringExtra("ts"));
                loadLiveTable();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        DailyStorage.ensureToday(this);
        buildUi(); loadSavedInputs(); requestNotificationPermission(); loadSavedLiveData(); loadLiveTable(); updateScheduleLabel();
        showPendingSignalIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        DailyStorage.ensureToday(this);
        IntentFilter f = new IntentFilter(); f.addAction("com.eren.gumustakip.UPDATE_UI");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updateReceiver, f);
        loadSavedLiveData(); loadLiveTable(); updateScheduleLabel(); showPendingSignalIfNeeded();
    }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {} }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(24)); root.setBackgroundColor(Color.rgb(11,16,24)); scroll.addView(root);

        ImageView cover=new ImageView(this); cover.setImageResource(com.eren.gumustakip.R.drawable.gumus_cover); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams cp=matchWrap(0); cp.height=dp(210); cp.setMargins(0,0,0,dp(14)); root.addView(cover,cp);
        TextView title=text("GÜMÜŞ TAKİP",28,Color.WHITE,true); root.addView(title,matchWrap(0));
        TextView sub=text("GetirFinans · XAG · kural tabanlı fiyat algoritması",14,Color.LTGRAY,false); root.addView(sub,matchWrap(0));

        LinearLayout card=card(); addLabel(card,"Portföy"); gramInput=input("Gümüş miktarı (gram) · örn. 250,50"); card.addView(gramInput,matchWrap(0)); costInput=input("Alış maliyeti (TL/gram) · örn. 78,25"); card.addView(costInput,matchWrap(0));
        LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); startButton=button("TAKİBİ BAŞLAT"); stopButton=button("DURDUR"); buttons.addView(startButton,weightWrap(1)); buttons.addView(stopButton,weightWrap(1)); card.addView(buttons,matchWrap(0)); root.addView(card,matchWrap(0));

        LinearLayout settings=card(); addLabel(settings,"Zamanlama Ayarları");
        controlIntervalInput=input("Site kontrol aralığı (saniye) · 40+ tavsiye edilir"); settings.addView(controlIntervalInput,matchWrap(0));
        TextView secInfo=text("Site kontrolü için 40 saniyenin altı önerilmez.",12,Color.LTGRAY,false); settings.addView(secInfo,matchWrap(0));
        scheduleView=text("Site kontrolü: 60 sn",13,Color.LTGRAY,false); settings.addView(scheduleView,matchWrap(0));
        Button saveSettings=button("AYARLARI KAYDET"); settings.addView(saveSettings,matchWrap(0)); root.addView(settings,matchWrap(0));

        LinearLayout live=card(); addLabel(live,"ANLIK VERİ"); priceView=text("Alış: —\nSatış: —\nSeviye: —",21,Color.WHITE,true); live.addView(priceView,matchWrap(0));
        portfolioView=text("Portföy: —\nKar/Zarar: —",16,Color.LTGRAY,false); live.addView(portfolioView,matchWrap(0)); updateView=text("Son kontrol: —",13,Color.GRAY,false); live.addView(updateView,matchWrap(0)); statusView=text("Takip durumu: kapalı",14,Color.LTGRAY,false); live.addView(statusView,matchWrap(0));
        aiView=text("Algoritma: fiyat geçmişi oluşunca kısa/orta dönem hareketleri değerlendirir. İşlem otomatik yapılmaz.",14,Color.LTGRAY,false); live.addView(aiView,matchWrap(0)); Button answerSignal=button("ALGORİTMA BİLDİRİMİNİ YANITLA"); live.addView(answerSignal,matchWrap(0)); answerSignal.setOnClickListener(v->showPendingSignalIfNeeded()); Button historyButton=button("İŞLEM GEÇMİŞİ / KÂR-ZARAR"); live.addView(historyButton,matchWrap(0)); historyButton.setOnClickListener(v->showTradeHistory()); root.addView(live,matchWrap(0));

        LinearLayout table=card(); addLabel(table,"SON ANLIK VERİLER (30 KAYIT)"); liveTableView=text("Henüz veri yok.",13,Color.LTGRAY,false); table.addView(liveTableView,matchWrap(0)); root.addView(table,matchWrap(0));

        LinearLayout data=card(); addLabel(data,"VERİLER NEREDE KAYDEDİLİYOR?"); pathView=text(getDataPath(),12,Color.WHITE,false); data.addView(pathView,matchWrap(0));
        TextView fileName=text("Günlük dosya: gumus_fiyat_gecmisi.txt",13,Color.LTGRAY,true); data.addView(fileName,matchWrap(0));
        Button share=button("TXT DOSYASINI PAYLAŞ"); data.addView(share,matchWrap(0)); root.addView(data,matchWrap(0));

        TextView info=text("Fiyatlar düzenli kaydedilir. Algoritma kısa ve orta dönem değişimlerini değerlendirir; işlem otomatik yapılmaz. Bildirime cevap verilmezse hatırlatma tekrarlanır. Alım-satım işlemlerini yalnızca gerçekten yaptıysan kaydet.",13,Color.GRAY,false); root.addView(info,matchWrap(0));
        startButton.setOnClickListener(v->startTracking()); stopButton.setOnClickListener(v->stopTracking()); saveSettings.setOnClickListener(v->saveSettings()); share.setOnClickListener(v->shareTxt()); setContentView(scroll);
    }

    private void startTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission();
            Toast.makeText(this, "Sürekli takip için bildirim iznini verip tekrar başlat.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent in = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    startActivity(in);
                    Toast.makeText(this, "Pil kısıtlaması ekranından izin ver; sonra TAKİBİ BAŞLAT'a yeniden bas.", Toast.LENGTH_LONG).show();
                } catch (Exception e) { showBackgroundReliabilityHelp(); }
                return;
            }
        }
        saveSettings(); double grams=parse(gramInput.getText().toString()), cost=parse(costInput.getText().toString()); if(grams<0||cost<0){Toast.makeText(this,"Değerleri kontrol et.",Toast.LENGTH_SHORT).show();return;}
        if(!prefs.getBoolean("portfolio_initialized",false)){prefs.edit().putFloat("initial_grams",(float)grams).putFloat("initial_cost",(float)cost).putBoolean("portfolio_initialized",true).apply();} prefs.edit().putString("grams",String.valueOf(grams)).putString("cost",String.valueOf(cost)).putBoolean("tracking_enabled",true).apply(); Intent i=new Intent(this,PriceTrackingService.class); i.setAction(PriceTrackingService.ACTION_START); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); statusView.setText("Takip durumu: aktif");
        if (!prefs.getBoolean("background_help_shown", false)) { prefs.edit().putBoolean("background_help_shown", true).apply(); showBackgroundReliabilityHelp(); }
    }
    private void stopTracking(){prefs.edit().putBoolean("tracking_enabled",false).apply();Intent i=new Intent(this,PriceTrackingService.class);i.setAction(PriceTrackingService.ACTION_STOP);startService(i);statusView.setText("Takip durumu: kapalı");}

    private void showBackgroundReliabilityHelp() {
        new AlertDialog.Builder(this).setTitle("Arka plan takibi için izinler")
            .setMessage("Takibin daha uzun süre çalışması için:\n\n1. Pil kullanımını 'Kısıtlanmamış' yap.\n2. Telefon ayarlarında uygulamanın arka plan kullanımına izin ver.\n3. Varsa 'Otomatik başlat' iznini aç.\n4. Son uygulamalar ekranında uygulamayı kilitle.\n5. Bildirim iznini açık tut.\n\nÜreticiye göre ayar isimleri değişebilir. Android, kullanıcı zorla durdurursa veya üretici sistemi kapatırsa kesintisiz çalışmayı garanti etmez. Sürekli takip pil tüketimini artırır.")
            .setPositiveButton("Uygulama ayarları",(d,w)->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}})
            .setNeutralButton("Pil ayarları",(d,w)->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){}})
            .setNegativeButton("Kapat",null).show();
    }

    private void saveSettings(){ long control=Math.max(40,(long)parse(controlIntervalInput.getText().toString())); prefs.edit().putLong("control_interval_sec",control).apply(); controlIntervalInput.setText(String.valueOf(control)); updateScheduleLabel(); Toast.makeText(this,"Ayarlar kaydedildi: site "+control+" sn",Toast.LENGTH_SHORT).show(); }
    private void loadSavedInputs(){gramInput.setText(prefs.getString("grams",""));costInput.setText(prefs.getString("cost","")); long seconds=prefs.getLong("control_interval_sec",0); if(seconds<=0){ long oldMin=prefs.getLong("control_interval_min",1); seconds=Math.max(40,oldMin*60); prefs.edit().putLong("control_interval_sec",seconds).remove("control_interval_min").apply(); } controlIntervalInput.setText(String.valueOf(seconds));}
    private void updateScheduleLabel(){if(scheduleView!=null)scheduleView.setText("Site kontrolü: "+prefs.getLong("control_interval_sec",60)+" sn · Algoritma: aktif");}

    private void loadSavedLiveData(){double sell=prefs.getFloat("last_sell",0),buy=prefs.getFloat("last_buy",0);String ts=prefs.getString("last_ts","—");if(sell>0){showLive(buy,sell,parse(gramInput.getText().toString()),parse(costInput.getText().toString()),ts);}String advice=prefs.getString("signal_message","");if(!advice.isEmpty()&&aiView!=null)aiView.setText("Son algoritma sinyali:\n"+advice);}
    private void loadLiveTable(){if(liveTableView==null)return;try{JSONArray a=new JSONArray(prefs.getString("live_rows","[]"));StringBuilder sb=new StringBuilder();sb.append(String.format(Locale.US,"%-10s %-11s %-11s\n","Saat","Alış","Satış"));sb.append("────────────────────────\n");for(int i=a.length()-1;i>=0;i--){JSONObject o=a.getJSONObject(i);sb.append(String.format(Locale.US,"%-10s %-11.4f %-11.4f\n",o.optString("time",""),o.optDouble("buy",0),o.optDouble("sell",0)));}liveTableView.setText(sb.length()>40?sb.toString():"Henüz veri yok.");}catch(Exception e){liveTableView.setText("Anlık tablo okunamadı.");}}

    private String getDataPath(){return new File(getFilesDir(),"gumus_fiyat_gecmisi.txt").getAbsolutePath();}
    private void shareTxt(){File f=new File(getFilesDir(),"gumus_fiyat_gecmisi.txt");if(!f.exists()){Toast.makeText(this,"Henüz TXT kaydı oluşmadı.",Toast.LENGTH_SHORT).show();return;}Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent s=new Intent(Intent.ACTION_SEND);s.setType("text/plain");s.putExtra(Intent.EXTRA_STREAM,uri);s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(s,"Günlük TXT dosyasını paylaş"));}

    private void showPendingSignalIfNeeded() {
        if (!prefs.getBoolean("signal_pending", false)) { return; }
        String message=prefs.getString("signal_message","Gümüş işlem önerisi");
        new AlertDialog.Builder(this).setTitle("Algoritma önerisi").setMessage(message+"\n\nBu öneriye göre işlem yaptın mı?")
            .setPositiveButton("Evet, yaptım",(d,w)->askTradeDetails())
            .setNegativeButton("Hayır, yapmadım",(d,w)->{prefs.edit().putBoolean("signal_pending",false).putLong("signal_answered_at",System.currentTimeMillis()).apply();Toast.makeText(this,"Yanıt kaydedildi.",Toast.LENGTH_SHORT).show();})
            .setNeutralButton("Daha sonra",(d,w)->Toast.makeText(this,"Hatırlatma devam edecek.",Toast.LENGTH_SHORT).show()).show();
    }
    private void askTradeDetails() {
        String action=prefs.getString("signal_action","SELL");
        double price=prefs.getFloat("signal_price",0);
        EditText qty=input("Gerçek işlem miktarı (gram)");
        new AlertDialog.Builder(this).setTitle(action.equals("SELL")?"Satış miktarı":"Alım miktarı")
            .setMessage(String.format(Locale.US,"İşlem fiyatı: %.4f TL/g. Yalnızca gerçekten yaptığın miktarı gir.",price)).setView(qty)
            .setNegativeButton("İptal",null).setPositiveButton("İşlemi kaydet",(d,w)->{
                double q=parse(qty.getText().toString()); double grams=parse(prefs.getString("grams","0")); double cost=parse(prefs.getString("cost","0"));
                if(q<=0 || price<=0){Toast.makeText(this,"Miktar/fiyat geçersiz.",Toast.LENGTH_LONG).show();return;}
                double realized=prefs.getFloat("realized_profit",0);
                if(action.equals("SELL")) { if(q>grams){Toast.makeText(this,"Elindeki miktardan fazla satış kaydedilemez.",Toast.LENGTH_LONG).show();return;} realized+=(price-cost)*q; grams-=q; }
                else { double oldCost=grams*cost; grams+=q; cost=grams>0?(oldCost+q*price)/grams:price; }
                JSONArray log;try{log=new JSONArray(prefs.getString("trade_log","[]"));}catch(Exception ex){log=new JSONArray();}
                JSONObject item=new JSONObject();try{item.put("type",action);item.put("grams",q);item.put("price",price);item.put("realized",action.equals("SELL")?(price-cost)*q:0);item.put("time",new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(new java.util.Date()));log.put(item);}catch(Exception ignored){}
                prefs.edit().putString("grams",String.valueOf(grams)).putString("cost",String.valueOf(cost)).putFloat("realized_profit",(float)realized).putString("trade_log",log.toString()).putBoolean("signal_pending",false).putLong("signal_answered_at",System.currentTimeMillis()).apply();
                gramInput.setText(String.valueOf(grams));costInput.setText(String.valueOf(cost));loadSavedLiveData();Toast.makeText(this,"Gerçekleşen işlem kaydedildi.",Toast.LENGTH_LONG).show();
            }).show();
    }
    private void showTradeHistory() {
        JSONArray log;try{log=new JSONArray(prefs.getString("trade_log","[]"));}catch(Exception e){log=new JSONArray();}
        StringBuilder b=new StringBuilder();for(int i=log.length()-1;i>=0;i--){JSONObject o=log.optJSONObject(i);if(o!=null)b.append(o.optString("time")).append(" · ").append(o.optString("type")).append(" · ").append(o.optDouble("grams",0)).append(" g · ").append(o.optDouble("price",0)).append(" TL/g\n");}
        b.append("\nGerçekleşmiş K/Z: ").append(String.format(Locale.US,"%+.2f TL",prefs.getFloat("realized_profit",0)));
        new AlertDialog.Builder(this).setTitle("İşlem geçmişi").setMessage(b.length()>0?b.toString():"Henüz kaydedilmiş işlem yok.").setPositiveButton("Kapat",null).show();
    }

    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.GRAY);e.setTextColor(Color.WHITE);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setPadding(dp(12),dp(8),dp(12),dp(8));return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(12);return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(14),dp(16),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(24,31,42));bg.setCornerRadius(dp(14));l.setBackground(bg);LinearLayout.LayoutParams p=matchWrap(0);p.setMargins(0,dp(14),0,0);l.setLayoutParams(p);return l;}
    private void addLabel(LinearLayout parent,String s){TextView t=text(s,13,Color.LTGRAY,true);parent.addView(t,matchWrap(0));}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);t.setPadding(0,dp(6),0,dp(6));return t;}
    private LinearLayout.LayoutParams matchWrap(int h){return new LinearLayout.LayoutParams(-1,h==0?-2:h);}
    private LinearLayout.LayoutParams weightWrap(float w){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2);p.weight=w;return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    private double parse(String s){try{return Double.parseDouble(s.trim().replace(",","."));}catch(Exception e){return 0;}}

    public void showLive(double buy,double sell,double grams,double cost,String ts){runOnUiThread(()->{priceView.setText(String.format(Locale.US,"Alış: %.4f TL\nSatış: %.4f TL\nSeviye: %d TL",buy,sell,(int)Math.floor(sell)));double total=grams*cost,value=grams*sell,profit=value-total,pct=total>0?profit/total*100:0;portfolioView.setText(String.format(Locale.US,"Eldeki gümüş: %.3f g\nPortföy Değeri: %.2f TL\nEldeki miktarın K/Z: %+.2f TL (%+.2f%%)\nGerçekleşmiş K/Z: %+.2f TL\nToplam K/Z: %+.2f TL",grams,value,profit,pct,prefs.getFloat("realized_profit",0),profit+prefs.getFloat("realized_profit",0)));updateView.setText("Son kontrol: "+ts);statusView.setText("Takip durumu: aktif");});}
}
