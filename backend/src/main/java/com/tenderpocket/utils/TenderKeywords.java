package com.tenderpocket.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Product keywords and tagging rules shared by every tender source (GeM, state portals).
 *
 * <p>The list was previously private to {@code GeMScraperService}; it lives here so the state
 * portal crawler filters with exactly the same vocabulary.
 */
public final class TenderKeywords {

    public static final List<String> RAW_KEYWORDS = Collections.unmodifiableList(Arrays.asList(

            "EXAMINATION TABLE", "Active Cold Box", "BIPAP", "Blood Bag Sealer", "Blood Bag Tube Sealer", 
            "Boyle's Machine", "C-ARM Machine", "Cautery Machine", "Cell Counter", "Chemistry Analyser", 
            "Colorimeter", "Colour Doppler", "CPAP", "Crash Cart", "CTG Machine", "Defibrillator", 
            "Ward Plain Bed", "Dielectric Sealer", "Digital Analytical Balance", "Digital X-Ray", 
            "Double Pan Balance", "Dressing Drum", "Dressing Trolley", "DVT Pump", "ECG Machine", 
            "Electric Bed", "Electro Surgical Unit", "Electrolyte", "Electrosurgical Device", 
            "Examination Couch", "Fetal Doppler", "Fogger Machine", "Foot Step", "Four Seater Chair Cushion", 
            "Fowler Bed", "Gel Card Incubator", "Haematology Analyzer", "Hematology Analyzer", 
            "Hemoglobin Analyzer", "Hemoglobinometer", "Holder Machine", "Hospital Bed", "Hospital Chair", 
            "Hospital Furniture", "Hospital Table", "Hospital Trolley", "ICU Bed", "Instrument Trolley", 
            "IV Rod", "Modular Operation Theatre", "Laminar Cabinet", "Laparoscopy Set", "Laparoscopy Trolley", 
            "LDR Bed", "Mattress for ICU", "Mattress for Semi Fowler", "Medical Equipment", 
            "Medical Gas Pipeline System", "Medical Hospital Lamps", "Microscope", "Modular Multipara Monitor", 
            "Office Chair", "Operating Theatre Pendant", "Operation Table", "OT LED Light Double Dome", 
            "OT Pendant", "OT Table Electric cum Manual", "Over Bed Table", "Oxygen Concentrator", 
            "Patient Monitor", "Pediatric Bed", "PH Meter", "Plain Bed", "Pulse Oximeter", "Radiant Warmer", 
            "Radiology & Imaging", "Recovery Trolley Hydraulic", "Revolving Stool", "RH View Box", 
            "Rolling Hypothermia Treatment Carts", "Fradi", "Semi Auto Analyser", "Semi Fowler Bed", 
            "Sterile Connecting Device", "Stretcher", "Suction Machine", "Syringe Pump", "Thermometer", 
            "Three Seater Chair", "TMT Machine", "Tube Stripper", "Ultrasonic Cleaner", "Ultrasound Machine", 
            "Ultrasound USG Machine", "USG Machine", "VDRL Shaker", "Ventilator", "Visi Cooler", 
            "Visual Light Box", "Volumetric Infusion Pump", "Weighing Scale", "X-Ray Machine", "IVD Pathology", 
            "Laproscopy and endoscopy", "Commercial Refrigeration", "Freezer / Deep Freezer", "Chest Freezer", 
            "Chiller", "Insulated Truck / Vehicles", "Insulated Van / Vehicles", "Refrigerated Vans / Trucks / Vehicles", 
            "Refrigerator", "Remote Temperature Monitoring Device (WHO Tested)", 
            "Solar Walk In Cooler and Freezer / Cold Room", "Walk-In-Freezer / Cold Room", "Steel Cupboards & Funriture Range", 
            "3 door steel cupboards", "2 door steel cupboards", "Office steel cupboards", 
            "Particle Board Box bed Particle Board", "Particle Board Single Bed", "Particle Board Double Bed", 
            "Particle Board Diwan", "Particle Board 3 Door Cupboard", "Particle Board 2 Door Cupboard", 
            "Particle Board Dressing Table", "Particle Board TV Showcase", "Particle Board Office Table", 
            "Steel Funriture", "ALMIRAH"
    ));

    /**
     * Generic goods words that, inside a health-sector department, are enough to treat a tender as
     * a likely equipment or furniture purchase even when no specific product keyword appears
     * (e.g. "Supply of Equipment and Instrument for District General Hospital").
     */
    private static final Pattern GENERIC_GOODS = Pattern.compile(
            "(?i)\\b(equipments?|instruments?|machines?|machinery|furniture|appliances?|apparatus|beds?|"
                    + "trolleys?|refrigerat\\w*|freezers?|cold\\s*(chain|room|box)|cupboards?|almirah\\w*|"
                    + "analy[sz]ers?|monitors?|ventilators?|x-?ray|ultrasound|usg|ecg|oxygen)\\b");

    private static final Map<String, Pattern> WHOLE_WORD_CACHE = new ConcurrentHashMap<>();

    private TenderKeywords() {}

    /**
     * Counts distinct keywords contained in the content as plain substrings (case-insensitive).
     * This is the original GeM behaviour and is kept unchanged for GeM.
     */
    public static int countMatchingKeywords(String content, List<String> keywords) {
        if (content == null || content.trim().isEmpty() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        Set<String> matchedKeywords = new HashSet<>();
        for (String kw : keywords) {
            if (kw == null) continue;
            String trimmedKw = kw.trim().toLowerCase(Locale.ROOT);
            if (trimmedKw.length() < 3) continue;
            if (lowerContent.contains(trimmedKw)) {
                matchedKeywords.add(trimmedKw);
            }
        }
        return matchedKeywords.size();
    }

    /**
     * Returns the first keyword found in the text as a whole word or phrase, or null.
     * Word boundaries stop short keywords matching inside unrelated words, which matters for free
     * text tender titles from state portals.
     */
    public static String firstWholeWordKeyword(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null) return null;
        for (String kw : keywords) {
            if (kw == null) continue;
            String k = kw.trim();
            if (k.length() < 3) continue;
            Pattern p = WHOLE_WORD_CACHE.computeIfAbsent(k.toLowerCase(Locale.ROOT), key ->
                    Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(key) + "(?![\\p{L}\\p{N}])"));
            if (p.matcher(text).find()) return k;
        }
        return null;
    }

    /**
     * Hindi product words for state portals that publish titles in Devanagari (Chhattisgarh). Plain
     * substring match: word-boundary rules do not suit Devanagari combining marks.
     */
    public static final List<String> HINDI_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "\u091a\u093f\u0915\u093f\u0924\u094d\u0938\u093e \u0909\u092a\u0915\u0930\u0923", // chikitsa upkaran (medical equipment)
            "\u091a\u093f\u0915\u093f\u0924\u094d\u0938\u0940\u092f \u0909\u092a\u0915\u0930\u0923", // chikitsiya upkaran
            "\u092a\u0932\u0902\u0917", // palang (bed)
            "\u092b\u0930\u094d\u0928\u0940\u091a\u0930", // furniture
            "\u0905\u0932\u092e\u093e\u0930\u0940", // almari (almirah)
            "\u0930\u0947\u092b\u094d\u0930\u093f\u091c\u0930\u0947\u091f\u0930", // refrigerator
            "\u092b\u094d\u0930\u0940\u091c\u0930", // freezer
            "\u0938\u094d\u091f\u094d\u0930\u0947\u091a\u0930", // stretcher
            "\u0935\u094d\u0939\u0940\u0932\u091a\u0947\u092f\u0930", // wheelchair
            "\u0911\u092a\u0930\u0947\u0936\u0928 \u0925\u093f\u090f\u091f\u0930", // operation theatre
            "\u0905\u0938\u094d\u092a\u0924\u093e\u0932 \u0909\u092a\u0915\u0930\u0923" // aspatal upkaran (hospital equipment)
    ));

    public static String firstHindiKeyword(String text) {
        if (text == null || text.isBlank()) return null;
        for (String k : HINDI_KEYWORDS) if (text.contains(k)) return k;
        return null;
    }

    public static boolean mentionsGenericGoods(String text) {
        return text != null && GENERIC_GOODS.matcher(text).find();
    }

    /** Business vertical tag, same rules as the GeM importer. */
    public static String verticalFor(String title) {
        if (title == null) return "Others";
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("wheelchair") || t.contains("trolley") || t.contains("couch") || t.contains("screen")
                || t.contains("cart") || t.contains("cabinet") || t.contains("ward") || t.contains("bed")
                || t.contains("furniture") || t.contains("table")) {
            return "Medical Equipment & Furniture";
        } else if (t.contains("centrifuge")) {
            return "Centrifuge";
        } else if (t.contains("freezer")) {
            return "Deep Freezer";
        } else if (t.contains("hvac") || t.contains("conditioning") || t.contains("split ac")
                || t.contains("chiller") || t.contains("cooling")) {
            return "HVAC";
        }
        return "Others";
    }
}
