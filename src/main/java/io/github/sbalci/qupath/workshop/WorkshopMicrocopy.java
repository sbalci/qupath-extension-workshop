package io.github.sbalci.qupath.workshop;

import java.util.List;

/**
 * JavaFX veya PathPrefs gerektirmeyen Türkçe arayüz etiketleri ve çift kitleli
 * (🩺 Klinik + 💻 Matematik) ipucu metinleri. Birim testlerinde doğrudan sınanır.
 */
public final class WorkshopMicrocopy {

    private WorkshopMicrocopy() {}

    public static final List<String> KNOWN_KEYS = List.of(
        "atolye.nuclear1", "atolye.nuclear2", "atolye.nuclear3",
        "atolye.membrane1", "atolye.membrane2", "atolye.membrane3",
        "atolye.cyto1", "atolye.cyto2", "atolye.cyto3",
        "atolye.pixDab1", "atolye.pixDab2", "atolye.pixDab3", "atolye.pixHmask",
        "atolye.cellposeModel", "atolye.cellposeDiameter", "atolye.pixelSize",
        "atolye.cellExpansionNuclear", "atolye.cellExpansionCyto",
        "atolye.cellposeFamily", "atolye.cellposeFlow", "atolye.cellposeCellprob",
        "atolye.stardistThreshold", "atolye.stardistPixelSize", "atolye.stardistCellExpansion",
        "atolye.sigma", "atolye.backgroundRadius", "atolye.minArea", "atolye.maxArea",
        "atolye.medianRadius", "atolye.detectionThreshold", "atolye.detectionChannel",
        "atolye.watershed", "atolye.pixScale",
        "atolye.classifierName", "atolye.minObjectArea", "atolye.minHoleArea", "atolye.maxTrainingSamples",
        "atolye.warnNuclearCount", "atolye.warnGenericCount", "atolye.warnTissueAreaMm2",
        "atolye.exportFolder", "atolye.exportSeparator", "atolye.exportScope",
        "atolye.exportDet", "atolye.exportAnn", "atolye.exportGeo",
        "atolye.exportColumnsMode", "atolye.exportColumns", "atolye.exportPrefix",
        "atolye.exportDateSubfolder", "atolye.exportLocation"
    );

    public static String labelFor(String key) {
        switch (key) {
            case "atolye.nuclear1": return "Nükleer 1+";
            case "atolye.nuclear2": return "Nükleer 2+";
            case "atolye.nuclear3": return "Nükleer 3+";
            case "atolye.membrane1": return "Membran 1+";
            case "atolye.membrane2": return "Membran 2+";
            case "atolye.membrane3": return "Membran 3+";
            case "atolye.cyto1": return "Sitoplazmik 1+";
            case "atolye.cyto2": return "Sitoplazmik 2+";
            case "atolye.cyto3": return "Sitoplazmik 3+";
            case "atolye.pixDab1": return "Piksel H-score 1+";
            case "atolye.pixDab2": return "Piksel H-score 2+";
            case "atolye.pixDab3": return "Piksel H-score 3+";
            case "atolye.pixHmask": return "Piksel Hematoksilen maskesi";
            case "atolye.cellposeModel": return "Cellpose modeli";
            case "atolye.cellposeDiameter": return "Cellpose çapı (px)";
            case "atolye.cellposeFamily": return "Cellpose model ailesi";
            case "atolye.cellposeFlow": return "Cellpose hücre sınırı toleransı (flow)";
            case "atolye.cellposeCellprob": return "Cellpose soluk hücre eşiği (cellprob)";
            case "atolye.stardistThreshold": return "StarDist olasılık eşiği";
            case "atolye.stardistPixelSize": return "StarDist piksel boyutu (µm)";
            case "atolye.stardistCellExpansion": return "StarDist hücre genişlemesi (µm)";
            case "atolye.pixelSize": return "Piksel boyutu (µm)";
            case "atolye.cellExpansionNuclear": return "Hücre genişlemesi — nükleer (µm)";
            case "atolye.cellExpansionCyto": return "Hücre genişlemesi — sitoplazma (µm)";
            case "atolye.sigma": return "Gürültü yumuşatma — Sigma (µm)";
            case "atolye.backgroundRadius": return "Arka plan yarıçapı (µm)";
            case "atolye.minArea": return "En küçük alan (µm²)";
            case "atolye.maxArea": return "En büyük alan (µm²)";
            case "atolye.medianRadius": return "Medyan filtre (µm)";
            case "atolye.detectionThreshold": return "Çekirdek eşiği (OD)";
            case "atolye.detectionChannel": return "Tespit kanalı";
            case "atolye.watershed": return "Bitişik çekirdekleri ayır (Watershed)";
            case "atolye.pixScale": return "Piksel ölçeği (alt örnekleme)";
            case "atolye.classifierName": return "Sınıflandırıcı adı";
            case "atolye.minObjectArea": return "En küçük nesne alanı (µm²)";
            case "atolye.minHoleArea": return "En küçük boşluk alanı (µm²)";
            case "atolye.maxTrainingSamples": return "En fazla eğitim örneği (0 = sınırsız)";
            case "atolye.warnNuclearCount": return "Az çekirdek uyarı eşiği (Nielsen önerisi)";
            case "atolye.warnGenericCount": return "Uyarı: düşük hücre sayısı";
            case "atolye.warnTissueAreaMm2": return "Uyarı: küçük doku (mm²)";
            case "atolye.exportFolder": return "Dışa aktarma klasörü";
            case "atolye.exportSeparator": return "Ayraç";
            case "atolye.exportScope": return "Varsayılan kapsam";
            case "atolye.exportDet": return "Dışa aktar: tespit TSV";
            case "atolye.exportAnn": return "Dışa aktar: anotasyon TSV";
            case "atolye.exportGeo": return "Dışa aktar: anotasyon GeoJSON";
            case "atolye.exportColumnsMode": return "Sütun seçimi";
            case "atolye.exportColumns": return "Seçili sütunlar (| ile)";
            case "atolye.exportPrefix": return "Dosya adı öneki";
            case "atolye.exportDateSubfolder": return "Tarihli alt klasör";
            case "atolye.exportLocation": return "Kayıt yeri (boş = proje/exports)";
            default: return key;
        }
    }

    public static String tooltipFor(String key) {
        switch (key) {
            case "atolye.nuclear1": return "🩺 Klinik: Çok soluk nükleer DAB boyanmasını negatif çekirdekten ayıran alt sınır (Ki-67, ER, PR).\n💻 Matematik: Çekirdek içi ortalama Beer–Lambert DAB OD ≥ bu değer ise ≥1+ atanır (tipik 0.10–0.25, varsayılan 0.20).";
            case "atolye.nuclear2": return "🩺 Klinik: Orta şiddette kahverengi nükleer İHK boyanma sınırı (H-score'da ×2 ağırlık alır).\n💻 Matematik: Çekirdek DAB OD mean ≥ bu değer ise ≥2+ atanır; 1+ < 2+ < 3+ şarttır (varsayılan 0.40).";
            case "atolye.nuclear3": return "🩺 Klinik: Koyu, yoğun nükleer DAB boyanma sınırı (H-score'da ×3 ağırlık alır).\n💻 Matematik: Çekirdek DAB OD mean ≥ bu değer ise 3+ atanır (tipik 0.50–0.80, varsayılan 0.60).";
            case "atolye.membrane1": return "🩺 Klinik: Zayıf/silik membranöz DAB halkasını negatiften ayıran alt sınır (HER2 İHK).\n💻 Matematik: Hücre zarı kompartmanında DAB OD mean ≥ bu değer ise ≥1+ atanır (varsayılan 0.15).";
            case "atolye.membrane2": return "🩺 Klinik: Orta şiddette çevresel membranöz DAB boyanma sınırı.\n💻 Matematik: Membran DAB OD mean ≥ bu değer ise ≥2+ atanır (tipik 0.30–0.50, varsayılan 0.40).";
            case "atolye.membrane3": return "🩺 Klinik: Güçlü, kalın membranöz DAB boyanma sınırı.\n💻 Matematik: Membran DAB OD mean ≥ bu değer ise 3+ atanır (tipik 0.55–0.85, varsayılan 0.70).";
            case "atolye.cyto1": return "🩺 Klinik: Makrofaj/sitoplazmik belirteçlerde (CD68) hafif granüler DAB sınırı.\n💻 Matematik: Sitoplazma halkası DAB OD mean ≥ bu değer ise ≥1+ atanır (varsayılan 0.10).";
            case "atolye.cyto2": return "🩺 Klinik: Orta yoğunlukta sitoplazmik DAB boyanma sınırı.\n💻 Matematik: Sitoplazma DAB OD mean ≥ bu değer ise ≥2+ atanır (varsayılan 0.20).";
            case "atolye.cyto3": return "🩺 Klinik: Yoğun sitoplazmik DAB boyanma sınırı.\n💻 Matematik: Sitoplazma DAB OD mean ≥ bu değer ise 3+ atanır (varsayılan 0.35).";
            case "atolye.pixDab1": return "🩺 Klinik: Tek tek hücre sınırı çizilemediğinde zayıf kahverengi doku piksellerini sayan eşik.\n💻 Matematik: Renk ayrıştırma DAB kanalında piksel OD ≥ bu değer ise 1+ piksel sayılır (varsayılan 0.10).";
            case "atolye.pixDab2": return "🩺 Klinik: Piksel düzeyinde orta şiddetli DAB boyalı alan sınırı.\n💻 Matematik: Piksel DAB OD ≥ bu değer ise 2+ dilimine girer (varsayılan 0.30).";
            case "atolye.pixDab3": return "🩺 Klinik: Piksel düzeyinde koyu DAB boyalı alan sınırı.\n💻 Matematik: Piksel DAB OD ≥ bu değer ise 3+ dilimine girer (varsayılan 0.60).";
            case "atolye.pixHmask": return "🩺 Klinik: Boş cam ve lümeni dışlayıp yalnız çekirdek/doku içeren pikselleri paydaya katar.\n💻 Matematik: Hematoksilen OD ≥ bu değer VEYA DAB ≥ 1+ koşulunu sağlayan pikseller toplam doku paydasını oluşturur (varsayılan 0.05).";
            case "atolye.cellposeModel": return "🩺 Klinik: HER2 membran modülünde hücre sınırlarını çizecek ön eğitimli derin öğrenme modeli.\n💻 Matematik: Cellpose ağ ağırlığı seçimi (cyto3: genel tam hücre, cyto2: önceki sürüm, nuclei: yalnız çekirdek).";
            case "atolye.cellposeDiameter": return "🩺 Klinik: Kesitteki tipik tümör hücresinin piksel cinsinden genişliği.\n💻 Matematik: Giriş karolarını ağın eğitim ölçeğine yeniden örnekleyen hedef çap (px; 0 = otomatik tahmin, varsayılan 25).";
            case "atolye.cellposeFamily": return "🩺 Klinik: Cellpose yardımcı sihirbazında seçilen bölütleme ailesi.\n💻 Matematik: Çıkarım omurgası (cyto3, cyto2, nuclei veya Transformer tabanlı cpsam).";
            case "atolye.cellposeFlow": return "🩺 Klinik: Düzensiz şekilli tümör hücrelerinin kabul edilme esnekliği.\n💻 Matematik: Üretilen gradyan akış alanı ile ağ tahmini arasındaki izin verilen maksimum MSE hatası (varsayılan 0.4).";
            case "atolye.cellposeCellprob": return "🩺 Klinik: Soluk boyanan hücrelerin yakalanma duyarlılığı (düşürülürse soluk hücreler de dahil edilir).\n💻 Matematik: Pikselin hücre içi sayılması için aşması gereken logit eşiği (-6 ile +6 arası, varsayılan 0.0).";
            case "atolye.stardistThreshold": return "🩺 Klinik: Üst üste binen kalabalık çekirdeklerde yanlış pozitif çekirdek sayımını dizginler.\n💻 Matematik: Yıldız-konveks poligon merkezlerinin NMS öncesi minimum nesne olasılık eşiği [0, 1] (varsayılan 0.5).";
            case "atolye.stardistPixelSize": return "🩺 Klinik: StarDist'in çekirdek ararken sanal lamı hangi büyütme çözünürlüğünde okuyacağı (0.5 µm ≈ 20×).\n💻 Matematik: Piramitten okunan karoların fiziksel örnekleme aralığı (µm/px > 0, varsayılan 0.5).";
            case "atolye.stardistCellExpansion": return "🩺 Klinik: Çekirdek sınırından dışarı doğru tahmin edilen sanal sitoplazma kuşağı genişliği.\n💻 Matematik: Komşu hücrelerle Voronoi sınırında çarpışana dek uygulanan Öklid genişleme yarıçapı (µm, varsayılan 5.0).";
            case "atolye.pixelSize": return "🩺 Klinik: Klasik çekirdek tespitinin çalışacağı mikroskop büyütme karşılığı (0.5 µm ≈ 20×).\n💻 Matematik: Watershed öncesi görüntünün yeniden örnekleneceği MPP değeri (µm/px; yarıya inerse piksel sayısı ×4 artar, varsayılan 0.5).";
            case "atolye.cellExpansionNuclear": return "🩺 Klinik: Nükleer İHK ve H&E analizlerinde çekirdek çevresine eklenen tahmini sitoplazma halkası.\n💻 Matematik: Çekirdek konturundan dışa doğru kısıtlı morfolojik dilasyon mesafesi (µm, varsayılan 5.0).";
            case "atolye.cellExpansionCyto": return "🩺 Klinik: Geniş sitoplazmalı makrofaj (CD68) ve epitel membran (HER2) ölçümleri için geniş halka.\n💻 Matematik: Çekirdekten dışa doğru Voronoi kısıtlı dilasyon yarıçapı (µm, varsayılan 7.0).";
            case "atolye.sigma": return "🩺 Klinik: Kaba kromatin topaklarının tek bir çekirdeği birden fazla parçaya bölmesini engeller.\n💻 Matematik: Tepe tespiti öncesi uygulanan 2B izotropik Gauss çekirdeğinin standart sapması σ (µm, varsayılan 1.5).";
            case "atolye.backgroundRadius": return "🩺 Klinik: Kesit kenarı veya kıvrım komşuluğundaki zemin boyanma dalgalanmasını dengeler.\n💻 Matematik: Morfolojik açma (opening) ile yerel arka plan çıkarımında kullanılan yapısal eleman yarıçapı (µm; 0 = kapalı, varsayılan 8.0).";
            case "atolye.minArea": return "🩺 Klinik: Apoptotik kırıntı, trombosit ve toz taneciklerinin hücre diye sayılmasını önler.\n💻 Matematik: Bölütlenen bağlı bileşenlerin kabul edilmesi için alt alan sınırı (µm², varsayılan 10.0).";
            case "atolye.maxArea": return "🩺 Klinik: Dev artefakt lekelerinin veya ayrışmamış büyük doku kitlelerinin tek hücre sayılmasını engeller.\n💻 Matematik: Bölütlenen çekirdek poligonu için üst alan sınırı (µm² ≥ minArea, varsayılan 400.0).";
            case "atolye.medianRadius": return "🩺 Klinik: Kamera/tarayıcı kaynaklı ince noktasal gürültüyü çekirdek kenarını bulandırmadan temizler.\n💻 Matematik: Doğrusal olmayan 2B medyan sıra-istatistiği filtresi yarıçapı (µm; 0 = kapalı, varsayılan 0.0).";
            case "atolye.detectionThreshold": return "🩺 Klinik: Soluk veziküler çekirdeklerin yakalanabilmesi için gereken en düşük boya koyuluğu.\n💻 Matematik: Arka plan çıkarımı sonrası çekirdek tohumlaması için minimum optik yoğunluk eşiği (OD, varsayılan 0.1).";
            case "atolye.detectionChannel": return "🩺 Klinik: Çekirdeklerin hangi boya bileşeninden aranacağını belirler (DAB çok koyuysa toplam OD seçilebilir).\n💻 Matematik: Dekonvolüsyon sonrası bölütleme giriş kanalı (Hematoxylin OD veya Optical density sum).";
            case "atolye.watershed": return "🩺 Klinik: Birbirine değen kalabalık tümör/lenfosit çekirdeklerini tek dev hücre yerine ayrı ayrı sayar.\n💻 Matematik: Mesafe dönüşümü ve yoğunluk vadileri üzerinde işaretçi kontrollü Watershed bölütlemesi (varsayılan açık).";
            case "atolye.pixScale": return "🩺 Klinik: Geniş doku alanlarında piksel H-score tarama hızını artırır.\n💻 Matematik: Piramit alt örnekleme (downsample) çarpanı ≥ 1.0 (1.0 = tam çözünürlük).";
            case "atolye.classifierName": return "🩺 Klinik: Eğittiğiniz tümör–stroma doku ayırıcısının proje içinde çağrılacağı ad.\n💻 Matematik: project/classifiers/pixel_classifiers/ altındaki JSON model tanımlayıcısı (varsayılan tumor-stroma-RF).";
            case "atolye.minObjectArea": return "🩺 Klinik: Stroma içindeki tekil yanlış pozitif piksel beneklerinin tümör adacığı sayılmasını engeller.\n💻 Matematik: Piksel sınıflandırıcı maskesinden poligon üretirken elenecek minimum bağlı bileşen alanı (µm², varsayılan 10000.0).";
            case "atolye.minHoleArea": return "🩺 Klinik: Tümör adası içindeki küçük bez lümeni veya çekirdek arası boşlukları tek parça tümör yatağında kapatır.\n💻 Matematik: Poligon topolojisinde doldurulacak maksimum iç boşluk (hole) alanı (µm², varsayılan 5000.0).";
            case "atolye.maxTrainingSamples": return "🩺 Klinik: Çok geniş eğitim çizimlerinde bilgisayarın donmasını önler.\n💻 Matematik: Random Forest / yapay sinir ağı matrisine rastgele örneklenecek üst piksel sınırı (0 = sınırsız, varsayılan 100000).";
            case "atolye.warnNuclearCount": return "🩺 Klinik: Az hücreli küçük biyopsi odaklarında yüzdelerin birkaç hücreyle sert oynayabileceği uyarısını verir.\n💻 Matematik: Nükleer İHK özetinde örneklem büyüklüğü N < bu eşik olduğunda tetiklenen kalite kontrol bayrağı (varsayılan 500).";
            case "atolye.warnGenericCount": return "🩺 Klinik: Genel sayım modüllerinde yetersiz hücre sayısına karşı kullanıcıyı uyarır.\n💻 Matematik: Toplam tespit sayısı N < bu eşik olduğunda sonuç raporuna uyarı satırı ekler (varsayılan 200).";
            case "atolye.warnTissueAreaMm2": return "🩺 Klinik: Çok dar seçilmiş alanların tüm kesiti temsil etmeyebileceğini hatırlatır.\n💻 Matematik: Seçili ROI alanı (mm²) < bu eşik olduğunda küçük örnekleme alanı uyarısı üretir (varsayılan 1.0 mm²).";
            case "atolye.exportFolder": return "🩺 Klinik: İstatistik/Excel analizine gidecek ölçüm tablolarının proje içinde toplanacağı klasör adı.\n💻 Matematik: Proje kök dizinine göreli çıktı alt dizini (varsayılan exports).";
            case "atolye.exportSeparator": return "🩺 Klinik: Türkçe Excel/R/Python'da ondalık virgül karışıklığını önlemek için TAB (.tsv) önerilir.\n💻 Matematik: Düz metin tablo sütun ayracı karakteri ('\\t' veya ',').";
            case "atolye.exportScope": return "🩺 Klinik: Yalnız ekrandaki sanal lamı mı yoksa projedeki tüm olguları mı tabloya dökeceğinizi seçer.\n💻 Matematik: İterasyon kapsamı ('image' = aktif ImageData, 'project' = tüm ProjectImageEntry listesi).";
            case "atolye.exportDet": return "🩺 Klinik: Her bir hücrenin ayrı satır olduğu ayrıntılı tabloyu dışa aktarır (yüz binlerce satır olabilir).\n💻 Matematik: PathDetectionObject ölçüm listelerinin serileştirilmesi anahtarı.";
            case "atolye.exportAnn": return "🩺 Klinik: Çizdiğiniz her tümör/odak bölgesinin tek özet satır olduğu tabloyu dışa aktarır.\n💻 Matematik: PathAnnotationObject özet ölçümlerinin serileştirilmesi anahtarı.";
            case "atolye.exportGeo": return "🩺 Klinik: Çizdiğiniz bölge sınırlarını başka yazılımlara veya Python/yapay zekâ iş akışlarına taşır.\n💻 Matematik: JTS poligon koordinatlarının RFC 7946 GeoJSON FeatureCollection olarak dışa aktarımı.";
            case "atolye.exportColumnsMode": return "🩺 Klinik: Tabloda yüzlerce ham sütun yerine yalnız seçtiğiniz özet ölçümleri tutmanızı sağlar.\n💻 Matematik: Sütun projeksiyon modu ('all' = tam şema, 'subset' = beyaz liste filtresi).";
            case "atolye.exportColumns": return "🩺 Klinik: Alt küme modunda tabloya yazılacak ölçüm başlıkları.\n💻 Matematik: '|' ayracıyla birleştirilmiş sütun adı dizgesi (boşsa varsayılan özet sütunlar).";
            case "atolye.exportPrefix": return "🩺 Klinik: Çalışma veya kohort kodunu dosya adına otomatik ekler (hasta adı yazmayın).\n💻 Matematik: Çıktı dosya adının başına eklenen sabit dizge öneki.";
            case "atolye.exportDateSubfolder": return "🩺 Klinik: Farklı günlerde yaptığınız analizlerin birbirinin üzerine yazılmasını önler.\n💻 Matematik: Çıktı yoluna ISO-8601 (YYYY-AA-GG) tarih alt dizini ekler.";
            case "atolye.exportLocation": return "🩺 Klinik: Tabloları proje klasörü dışında ortak bir araştırma klasörüne kaydetmek için kullanılır.\n💻 Matematik: Mutlak dizin yolu (boş bırakılırsa <proje>/<exportFolder> kullanılır).";
            default: return "";
        }
    }
}

