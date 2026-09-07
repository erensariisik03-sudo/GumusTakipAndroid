package com.eren.gumustakip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Yeni_Metin_Belgesi[1](1).txt içindeki yapay_zekaya_sor() mantığının Android karşılığı.
 */
public final class GeminiAnalyzer {
    // Kullanıcının verdiği scriptteki API anahtarı doğrudan burada tutuluyor.
    // Gerçek bir anahtar kullanıyorsanız kaynak kodu herkese açık GitHub deposuna göndermeyin.
    private static final String API_KEY = "AIzaSyDHsVhYC3CraENK4juA1LrgAPS83PqyN_0";
    private static final String[] MODELS = {"gemini-2.5-flash", "gemini-1.5-flash"};

    private GeminiAnalyzer() {}

    public static String ask(double gramMiktari, double maliyetFiyati, String fiyatTablosu) {
        String prompt =
                "Sen bir finansal asistansın. Elimde " + maliyetFiyati + " TL maliyetle aldığım "
                        + gramMiktari + " gram gümüş var. Piyasada son 30 dakikadaki fiyat değişimleri şu şekilde "
                        + "(Saat - Fiyat): " + fiyatTablosu + ". "
                        + "Bu verilere göre sence ne yapmalıyım? Satmalı mıyım, tutmalı mıyım, yoksa almaya devam mı etmeliyim? "
                        + "Lütfen çok kısa, net ve bir telefon bildirimine sığacak kadar öz (maksimum 2 cümle) bir tavsiye ver.";

        try {
            JSONObject part = new JSONObject().put("text", prompt);
            JSONArray parts = new JSONArray().put(part);
            JSONObject content = new JSONObject().put("role", "user").put("parts", parts);
            JSONArray contents = new JSONArray().put(content);
            JSONObject payload = new JSONObject().put("contents", contents);

            for (String model : MODELS) {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + API_KEY;

                for (int attempt = 0; attempt < 2; attempt++) {
                    HttpURLConnection connection = null;
                    try {
                        connection = (HttpURLConnection) new URL(endpoint).openConnection();
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(15000);
                        connection.setReadTimeout(30000);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                        connection.setRequestProperty("Accept", "application/json");

                        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                        try (OutputStream os = connection.getOutputStream()) {
                            os.write(body);
                        }

                        int code = connection.getResponseCode();
                        if (code == HttpURLConnection.HTTP_OK) {
                            String response = readAll(connection.getInputStream());
                            JSONObject res = new JSONObject(response);
                            JSONArray candidates = res.optJSONArray("candidates");
                            if (candidates != null && candidates.length() > 0) {
                                JSONObject candidate = candidates.optJSONObject(0);
                                if (candidate != null) {
                                    JSONObject responseContent = candidate.optJSONObject("content");
                                    JSONArray responseParts = responseContent != null
                                            ? responseContent.optJSONArray("parts") : null;
                                    if (responseParts != null && responseParts.length() > 0) {
                                        String text = responseParts.optJSONObject(0) != null
                                                ? responseParts.optJSONObject(0).optString("text", "") : "";
                                        if (!text.trim().isEmpty()) return text.trim();
                                    }
                                }
                            }
                            return "Yapay zeka yanıtı boş geldi.";
                        }

                        if (code == HttpURLConnection.HTTP_UNAVAILABLE) {
                            System.out.println("[" + model + "] 503 Sunucu Yoğun Hatası! Yeniden deneniyor...");
                            if (attempt == 0) sleep(2000);
                            continue;
                        }

                        System.out.println("[" + model + "] HTTP Hatası: " + code);
                        break;
                    } catch (Exception e) {
                        System.out.println("[" + model + "] Bağlantı hatası: " + e.getMessage());
                        break;
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Gemini istek hazırlama hatası: " + e.getMessage());
        }

        return "Yapay zeka sunucuları şu an yoğun, analiz yanıtı alınamadı.";
    }

    private static String readAll(InputStream input) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
