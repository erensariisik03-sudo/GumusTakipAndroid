package com.eren.gumustakip;

import android.Manifest;
import android.app.Activity;
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
    private EditText gramInput, costInput, controlIntervalInput, aiIntervalInput;
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
            } else if ("com.eren.gumustakip.UPDATE_AI".equals(intent.getAction())) {
                String advice = intent.getStringExtra("advice");
                if (advice != null && aiView != null) aiView.setText("Son Gemini tavsiyesi:\n" + advice);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gumus", MODE_PRIVATE);
        DailyStorage.ensureToday(this);
        buildUi(); loadSavedInputs(); requestNotificationPermission(); loadSavedLiveData(); loadLiveTable(); updateScheduleLabel();
    }

    @Override protected void onResume() {
        super.onResume();
        DailyStorage.ensureToday(this);
        IntentFilter f = new IntentFilter(); f.addAction("com.eren.gumustakip.UPDATE_UI"); f.addAction("com.eren.gumustakip.UPDATE_AI");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updateReceiver, f);
        loadSavedLiveData(); loadLiveTable(); updateScheduleLabel();
    }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {} }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(24)); root.setBackgroundColor(Color.rgb(11,16,24)); scroll.addView(root);

        ImageView cover=new ImageView(this); cover.setImageResource(com.eren.gumustakip.R.drawable.gumus_cover); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams cp=matchWrap(0); cp.height=dp(210); cp.setMargins(0,0,0,dp(14)); root.addView(cover,cp);
        TextView title=text("GÜMÜŞ TAKİP",28,Color.WHITE,true); root.addView(title,matchWrap(0));
        TextView sub=text("GetirFinans · XAG · otomatik fiyat + Gemini analizi",14,Color.LTGRAY,false); root.addView(sub,matchWrap(0));

        LinearLayout card=card(); addLabel(card,"Portföy"); gramInput=input("Gümüş miktarı (gram) · örn. 250,50"); card.addView(gramInput,matchWrap(0)); costInput=input("Alış maliyeti (TL/gram) · örn. 78,25"); card.addView(costInput,matchWrap(0));
        LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); startButton=button("TAKİBİ BAŞLAT"); stopButton=button("DURDUR"); buttons.addView(startButton,weightWrap(1)); buttons.addView(stopButton,weightWrap(1)); card.addView(buttons,matchWrap(0)); root.addView(card,matchWrap(0));

        LinearLayout settings=card(); addLabel(settings,"Zamanlama Ayarları");
        controlIntervalInput=input("Site kontrol aralığı (dakika)"); settings.addView(controlIntervalInput,matchWrap(0));
        aiIntervalInput=input("Yapay zekâ soru aralığı (dakika)"); settings.addView(aiIntervalInput,matchWrap(0));
        scheduleView=text("Site: 1 dk · Gemini: 60 dk",13,Color.LTGRAY,false); settings.addView(scheduleView,matchWrap(0));
        Button saveSettings=button("AYARLARI KAYDET"); settings.addView(saveSettings,matchWrap(0)); root.addView(settings,matchWrap(0));

        LinearLayout live=card(); addLabel(live,"ANLIK VERİ"); priceView=text("Alış: —\nSatış: —\nSeviye: —",21,Color.WHITE,true); live.addView(priceView,matchWrap(0));
        portfolioView=text("Portföy: —\nKar/Zarar: —",16,Color.LTGRAY,false); live.addView(portfolioView,matchWrap(0)); updateView=text("Son kontrol: —",13,Color.GRAY,false); live.addView(updateView,matchWrap(0)); statusView=text("Takip durumu: kapalı",14,Color.LTGRAY,false); live.addView(statusView,matchWrap(0));
        aiView=text("Son Gemini tavsiyesi: Henüz analiz yapılmadı.",14,Color.LTGRAY,false); live.addView(aiView,matchWrap(0)); root.addView(live,matchWrap(0));

        LinearLayout table=card(); addLabel(table,"SON ANLIK VERİLER (30 KAYIT)"); liveTableView=text("Henüz veri yok.",13,Color.LTGRAY,false); table.addView(liveTableView,matchWrap(0)); root.addView(table,matchWrap(0));

        LinearLayout data=card(); addLabel(data,"VERİLER NEREDE KAYDEDİLİYOR?"); pathView=text(getDataPath(),12,Color.WHITE,false); data.addView(pathView,matchWrap(0));
        TextView fileName=text("Günlük dosya: gumus_fiyat_gecmisi.txt",13,Color.LTGRAY,true); data.addView(fileName,matchWrap(0));
        Button share=button("TXT DOSYASINI PAYLAŞ"); data.addView(share,matchWrap(0)); root.addView(data,matchWrap(0));

        TextView info=text("Standart ayar: site 1 dakikada bir kontrol edilir, Gemini 60 dakikada bir son birikmiş verilerle analiz yapar. Her başarılı fiyat okuması aynı anda anlık tabloya, son kayıt geçmişine ve günlük TXT dosyasına yazılır.",13,Color.GRAY,false); root.addView(info,matchWrap(0));
        startButton.setOnClickListener(v->startTracking()); stopButton.setOnClickListener(v->stopTracking()); saveSettings.setOnClickListener(v->saveSettings()); share.setOnClickListener(v->shareTxt()); setContentView(scroll);
    }

    private void startTracking() {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) { android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE); if(pm!=null && !pm.isIgnoringBatteryOptimizations(getPackageName())) { Intent in=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:"+getPackageName())); startActivity(in); Toast.makeText(this,"Lütfen pil optimizasyonunu kapatın.",Toast.LENGTH_LONG).show(); return; } }
        saveSettings(); double grams=parse(gramInput.getText().toString()), cost=parse(costInput.getText().toString()); if(grams<0||cost<0){Toast.makeText(this,"Değerleri kontrol et.",Toast.LENGTH_SHORT).show();return;}
        prefs.edit().putString("grams",String.valueOf(grams)).putString("cost",String.valueOf(cost)).apply(); Intent i=new Intent(this,PriceTrackingService.class); i.setAction(PriceTrackingService.ACTION_START); if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i); statusView.setText("Takip durumu: aktif");
    }
    private void stopTracking(){Intent i=new Intent(this,PriceTrackingService.class);i.setAction(PriceTrackingService.ACTION_STOP);startService(i);statusView.setText("Takip durumu: kapalı");}

    private void saveSettings(){ long control=Math.max(1,(long)parse(controlIntervalInput.getText().toString())); long ai=Math.max(1,(long)parse(aiIntervalInput.getText().toString())); prefs.edit().putLong("control_interval_min",control).putLong("ai_interval_min",ai).apply(); updateScheduleLabel(); Toast.makeText(this,"Ayarlar kaydedildi: site "+control+" dk · Gemini "+ai+" dk",Toast.LENGTH_SHORT).show(); }
    private void loadSavedInputs(){gramInput.setText(prefs.getString("grams",""));costInput.setText(prefs.getString("cost",""));controlIntervalInput.setText(String.valueOf(prefs.getLong("control_interval_min",1)));aiIntervalInput.setText(String.valueOf(prefs.getLong("ai_interval_min",60)));}
    private void updateScheduleLabel(){if(scheduleView!=null)scheduleView.setText("Site kontrolü: "+prefs.getLong("control_interval_min",1)+" dk · Gemini analizi: "+prefs.getLong("ai_interval_min",60)+" dk");}

    private void loadSavedLiveData(){double sell=prefs.getFloat("last_sell",0),buy=prefs.getFloat("last_buy",0);String ts=prefs.getString("last_ts","—");if(sell>0){showLive(buy,sell,parse(gramInput.getText().toString()),parse(costInput.getText().toString()),ts);}String advice=prefs.getString("last_ai_advice","");if(!advice.isEmpty()&&aiView!=null)aiView.setText("Son Gemini tavsiyesi:\n"+advice);}
    private void loadLiveTable(){if(liveTableView==null)return;try{JSONArray a=new JSONArray(prefs.getString("live_rows","[]"));StringBuilder sb=new StringBuilder();sb.append(String.format(Locale.US,"%-10s %-11s %-11s\n","Saat","Alış","Satış"));sb.append("────────────────────────\n");for(int i=a.length()-1;i>=0;i--){JSONObject o=a.getJSONObject(i);sb.append(String.format(Locale.US,"%-10s %-11.4f %-11.4f\n",o.optString("time",""),o.optDouble("buy",0),o.optDouble("sell",0)));}liveTableView.setText(sb.length()>40?sb.toString():"Henüz veri yok.");}catch(Exception e){liveTableView.setText("Anlık tablo okunamadı.");}}

    private String getDataPath(){return new File(getFilesDir(),"gumus_fiyat_gecmisi.txt").getAbsolutePath();}
    private void shareTxt(){File f=new File(getFilesDir(),"gumus_fiyat_gecmisi.txt");if(!f.exists()){Toast.makeText(this,"Henüz TXT kaydı oluşmadı.",Toast.LENGTH_SHORT).show();return;}Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent s=new Intent(Intent.ACTION_SEND);s.setType("text/plain");s.putExtra(Intent.EXTRA_STREAM,uri);s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(s,"Günlük TXT dosyasını paylaş"));}

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

    public void showLive(double buy,double sell,double grams,double cost,String ts){runOnUiThread(()->{priceView.setText(String.format(Locale.US,"Alış: %.4f TL\nSatış: %.4f TL\nSeviye: %d TL",buy,sell,(int)Math.floor(sell)));double total=grams*cost,value=grams*sell,profit=value-total,pct=total>0?profit/total*100:0;portfolioView.setText(String.format(Locale.US,"Portföy Değeri: %.2f TL\nKar/Zarar: %+.2f TL (%+.2f%%)",value,profit,pct));updateView.setText("Son kontrol: "+ts);statusView.setText("Takip durumu: aktif");});}
}
