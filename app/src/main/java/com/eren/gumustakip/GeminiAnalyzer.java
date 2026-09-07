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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gemini AI katmanı.
 * bot.py'ye, Termux'a veya localhost sunucusuna bağımlı değildir.
 * bot.py'deki ana fikirler Android içinde uygulanır:
 * - generateContent destekleyen modelleri dinamik bulma
 * - 429/5xx için retry + exponential backoff
 * - model değiştirirken aynı prompt/veri bağlamını koruma
 */
public final class GeminiAnalyzer {
    // bot.py içindeki anahtar kullanılıyor. Kaynak kodu paylaşırken bu anahtarı yenilemeniz önerilir.
    private static final String API_KEY = "AQ.Ab8RN6K5mferVKngM-xOg3OK5ZONjyvxQnv8HlJwmgJpKwNfTQ";
    private static final String API_ROOT = "https://generativelanguage.googleapis.com/v1beta";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;
    private static final int MAX_RETRIES_PER_MODEL = 3;

    private GeminiAnalyzer() {}

    public static String ask(double gramMiktari,
                             double maliyetFiyati,
                             String kaydedilenVeriler,
                             long analizDakika) {
        String prompt = buildPrompt(gramMiktari, maliyetFiyati, kaydedilenVeriler, analizDakika);

        List<String> models = getAvailableModels();
        if (models.isEmpty()) {
            models = fallbackModels();
        }

        String firstError = null;
        for (String model : models) {
            GenerateResult result = generateContent(model, prompt);
            if (result.answer != null && !result.answer.trim().isEmpty()) {
                return result.answer.trim();
            }
            if (firstError == null) firstError = result.error;
        }

        if (firstError == null || firstError.trim().isEmpty()) {
            return "AI yanıtı alınamadı. Fiyat verileri kaydedilmeye devam ediyor.";
        }
        return "AI yanıtı alınamadı: " + firstError;
    }

    private static String buildPrompt(double grams,
                                      double cost,
                                      String savedData,
                                      long analysisMinutes) {
        return "GÜMÜŞ TAKİP SİSTEMİNDEN GELEN VERİLER\n"
                + "Sen uygulamanın içindeki analiz yardımcısısın. Yalnızca gönderilen verileri kullan; verilmeyen fiyatları uydurma.\n"
                + "ÖNEMLİ FİYAT KURALI: İki fiyatın BÜYÜK olanı ALIŞ, küçük olanı SATIŞ kabul edilir.\n"
                + "Portföy miktarı: " + String.format(Locale.US, "%.4f gram", grams) + "\n"
                + "Kullanıcının ortalama maliyeti: " + String.format(Locale.US, "%.4f TL/gram", cost) + "\n"
                + "Analiz penceresi: son " + analysisMinutes + " dakika\n\n"
                + "BUGÜN KAYDEDİLEN VERİLER\n"
                + ((savedData == null || savedData.trim().isEmpty()) ? "Veri yok.\n" : savedData.trim() + "\n")
                + "BUGÜN VERİLERİNİN SONU\n\n"
                + "Verilerdeki ilk ve son fiyatı karşılaştır; fiyat hareketini ve portföy K/Z durumunu özetle."
                + " Yalnızca verilen sayılara dayan. En fazla 3 kısa cümle yaz."
                + " Otomatik alım/satım emri verme."
                + " Bu uygulamadaki alış/satış isimlendirmesini değiştirme.";
    }

    private static List<String> getAvailableModels() {
        List<String> result = new ArrayList<>();
        try {
            String url = API_ROOT + "/models?key=" + API_KEY;
            JSONObject data = getJson(url);
            JSONArray models = data.optJSONArray("models");
            if (models == null) return result;

            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name", "").replace("models/", "").trim();
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean supportsGenerate = false;
                if (methods != null) {
                    for (int j = 0; j < methods.length(); j++) {
                        if ("generateContent".equals(methods.optString(j))) {
                            supportsGenerate = true;
                            break;
                        }
                    }
                }
                if (supportsGenerate && !name.isEmpty()) result.add(name);
            }
        } catch (Exception ignored) {
        }

        return orderModels(result);
    }

    private static List<String> orderModels(List<String> available) {
        Set<String> ordered = new LinkedHashSet<>();
        String[] preferred = {
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-1.5-flash"
        };

        for (String p : preferred) {
            if (available.contains(p)) ordered.add(p);
        }
        for (String model : available) {
            if (model.toLowerCase(Locale.ROOT).contains("flash")) ordered.add(model);
        }
        for (String model : available) ordered.add(model);

        return new ArrayList<>(ordered);
    }

    private static List<String> fallbackModels() {
        List<String> result = new ArrayList<>();
        result.add("gemini-2.5-flash");
        result.add("gemini-2.5-flash-lite");
        result.add("gemini-2.0-flash");
        result.add("gemini-1.5-flash");
        return result;
    }

    private static GenerateResult generateContent(String model, String prompt) {
        String urlString = API_ROOT + "/models/" + model + ":generateContent?key=" + API_KEY;
        JSONObject payload = new JSONObject();
        try {
            JSONArray contents = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", prompt));
            user.put("parts", parts);
            contents.put(user);
            payload.put("contents", contents);
        } catch (Exception e) {
            return new GenerateResult(null, "İstek hazırlanamadı");
        }

        for (int attempt = 0; attempt < MAX_RETRIES_PER_MODEL; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String responseText = readAll(stream);

                if (code == HttpURLConnection.HTTP_OK) {
                    JSONObject response = new JSONObject(responseText);
                    String text = extractText(response);
                    if (!text.isEmpty()) return new GenerateResult(text, null);
                    return new GenerateResult(null, "Model boş yanıt döndürdü");
                }

                if (code == 401 || code == 403) {
                    return new GenerateResult(null, "API anahtarı veya erişim izni hatası (HTTP " + code + ")");
                }

                if (code == 404) {
                    return new GenerateResult(null, "Model bulunamadı (HTTP 404)");
                }

                if (code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
                    if (attempt < MAX_RETRIES_PER_MODEL - 1) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    return new GenerateResult(null, "Geçici servis/quota sorunu (HTTP " + code + ")");
                }

                return new GenerateResult(null, "Gemini HTTP " + code);
            } catch (Exception e) {
                if (attempt < MAX_RETRIES_PER_MODEL - 1) {
                    sleepBackoff(attempt);
                    continue;
                }
                return new GenerateResult(null, "Ağ hatası");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        return new GenerateResult(null, "Bilinmeyen Gemini hatası");
    }

    private static JSONObject getJson(String urlString) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
            return new JSONObject(readAll(connection.getInputStream()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String extractText(JSONObject response) {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";

        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) return "";
        JSONObject content = candidate.optJSONObject("content");
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void sleepBackoff(int attempt) {
        try {
            long delay = Math.min(8000L, 1500L * (1L << attempt));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class GenerateResult {
        final String answer;
        final String error;
        GenerateResult(String answer, String error) {
            this.answer = answer;
            this.error = error;
        }
    }
}
