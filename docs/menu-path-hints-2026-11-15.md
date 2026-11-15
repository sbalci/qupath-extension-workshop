# Menu path hints — text-only script changes

15 November 2026, branch `fix/ask-first-and-rc9`. Audit finding Y6: hints that tell the
reader where a tool is in the menu skipped the topic group or named a label that does
not exist. 92 hints in 58 scripts and 4 lines in the two properties files were corrected
against the menu defined in `WorkshopExtension.java`. Text only: no logic changed. The
source fingerprints in `script-core.json` were updated because the fallback text is part
of the fingerprinted source.

Review status: prepared by an automated agent and checked by script (placeholders, keys,
compilation, unit tests). Not read line by line by a person and not checked in the QuPath
GUI. The table below is the list to read.

Root: `Extensions → Atölye` (both languages). Top menus: `Modüller` / `Modules`, `Yardımcılar` / `Utilities`.

## 1. Tool to group map (derived from WorkshopExtension.java + i18n properties)

Modules menu (`Modüller` / `Modules`) direct items: Arayüz turu / Interface tour; Hücre tespiti / Cell detection; Nükleer boya (Ki-67) / Nuclear stain (Ki-67); ER / PR H-score; Membran boya (HER2) / Membrane stain (HER2); Sitoplazmik boya (CD68) / Cytoplasmic stain (CD68); Tümör içi Ki-67 / Intra-tumoral Ki-67; QuANTUM cTCF; Veri dışa aktarma / Data export. Submenus: Doku tespiti / Tissue detection, Tümör/Stroma / Tumor/Stroma, Mitoz tespiti / Mitosis detection, Görüntü Hizalama / Image Registration.

| script | menu | group TR | group EN | label TR | label EN |
|---|---|---|---|---|---|
| sihirbaz.groovy | Modüller / Modules | Tümör/Stroma | Tumor/Stroma | Tümör/Stroma sihirbazı | Tumor/Stroma wizard |
| model-egit.groovy | Modüller / Modules | Tümör/Stroma | Tumor/Stroma | Adım 1 — Model eğit | Step 1 — Train model |
| tumor-stroma.groovy | Modüller / Modules | Tümör/Stroma | Tumor/Stroma | Adım 2 — Alan ölçümü | Step 2 — Area measurement |
| yardimci-doku-tespiti-sihirbaz.groovy | Modüller / Modules | Doku tespiti | Tissue detection | Yerleşik (Otsu / ContourTracing) | Built-in (Otsu / ContourTracing) |
| yardimci-esik-siniflandirici.groovy | Modüller / Modules | Doku tespiti | Tissue detection | Piksel eşikleyici (Create thresholder) | Pixel thresholder (Create thresholder) |
| yardimci-grandqc-sihirbaz.groovy | Modüller / Modules | Doku tespiti | Tissue detection | GrandQC modeli (doku ve artefakt, Python) | GrandQC model (tissue & artifact QC, Python) |
| yardimci-tiatoolbox-sihirbaz.groovy | Modüller / Modules | Doku tespiti | Tissue detection | TIA Toolbox maskesi (Otsu/morfoloji, Python) | TIA Toolbox mask (Otsu/morphology, Python) |
| yardimci-tiatoolbox-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | TIA Toolbox boyama normalizasyonu / doku maskesi sihirbazı | TIA Toolbox stain normalization / tissue mask wizard |
| yardimci-mitoz-merkez.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | Mitoz modelleri listesi | Mitosis models hub |
| yardimci-mitoz-tiatoolbox.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | KongNet MIDOG (TIA Toolbox) | KongNet MIDOG (TIA Toolbox) |
| yardimci-mitoz-fcos.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | MIDOG25 FCOS (torchvision) | MIDOG25 FCOS (torchvision) |
| yardimci-mitoz-retinanet.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | MIDOG DA-RetinaNet (2021, eski) | MIDOG DA-RetinaNet (2021, legacy) |
| yardimci-mitoz-atipik-effnet.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | Atipik sınıflandırma — MIDOG25 EffNetV2 | Atypical subtyping — MIDOG25 EffNetV2 |
| yardimci-mitoz-atipik-sanofi.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | Atipik sınıflandırma — Sanofi EFTD (kapılı) | Atypical subtyping — Sanofi EFTD (gated) |
| yardimci-mitoz-karsilastir.groovy | Modüller / Modules | Mitoz tespiti | Mitosis detection | Modelleri karşılaştır (aynı bölge) | Compare models (same region) |
| yardimci-valis-sihirbaz.groovy | Modüller / Modules | Görüntü Hizalama | Image Registration | VALIS (Docker/yerel) | VALIS (Docker/local) |
| yardimci-warpy-sihirbaz.groovy | Modüller / Modules | Görüntü Hizalama | Image Registration | Warpy (esnek; Fiji) | Warpy (non-rigid; Fiji) |
| yardimci-hizalama-aktarim.groovy | Modüller / Modules | Görüntü Hizalama | Image Registration | Afin (otomatik/elle) | Affine (automatic/manual) |
| yardimci-senkron-izgara.groovy | Modüller / Modules | Görüntü Hizalama | Image Registration | Senkron Izgara | Synchronized Grid |
| yardimci-tespitleri-sil.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Tespitleri sil | Clear detections |
| yardimci-image-type.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Görüntü tipi ayarla | Set image type |
| yardimci-esik-ayarla.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Eşikleri ayarla | Adjust thresholds |
| yardimci-kalibrasyon.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Kalibrasyon (piksel boyutu) | Calibration (pixel size) |
| yardimci-goruntu-kunye.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Görüntü künyesi ve histogram | Image metadata & channel histogram |
| yardimci-anotasyon-qc.groovy | Yardımcılar / Utilities | Temel araçlar | Basic tools | Anotasyon yapısı QC (denetçi) | Annotation geometry QC (inspector) |
| yardimci-boya-vektor-sihirbaz.groovy | Yardımcılar / Utilities | Boya ve renk | Stain & color | Boya vektörleri sihirbazı | Stain vectors wizard |
| yardimci-boya-kalite-qc.groovy | Yardımcılar / Utilities | Boya ve renk | Stain & color | Boya kalitesi QC ölçümü | Stain quality QC metrics |
| yardimci-icc-denetci-sihirbaz.groovy | Yardımcılar / Utilities | Boya ve renk | Stain & color | ICC renk profili denetçisi | ICC color profile inspector |
| yardimci-kohort-boya-qc.groovy | Yardımcılar / Utilities | Boya ve renk | Stain & color | Kohort boya OD sürüklenme raporu | Cohort stain OD drift report |
| yardimci-stardist-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | StarDist çekirdek tespiti sihirbazı | StarDist nucleus detection wizard |
| yardimci-cellpose-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | Cellpose hücre/çekirdek tespiti sihirbazı | Cellpose cell/nucleus detection wizard |
| yardimci-instanseg-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | InstanSeg çekirdek/hücre tespiti sihirbazı | InstanSeg nucleus/cell detection wizard |
| yardimci-hovernext-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | HoVer-NeXt çekirdek sınıflandırma sihirbazı (7 sınıf, GPU) | HoVer-NeXt nuclear classification wizard (7 classes, GPU) |
| yardimci-classpose-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | Classpose hücre fenotipleme sihirbazı (H&E) | Classpose cell phenotyping wizard (H&E) |
| yardimci-cytoformer-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | CytoFormer hücre tipi sınıflama sihirbazı (H&E, 16 organ) | CytoFormer cell-type classification wizard (H&E, 16 organs) |
| yardimci-histoplus-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | HistoPLUS hücre bölütleme + sınıflama sihirbazı (H&E, 13 sınıf) | HistoPLUS cell segmentation + classification wizard (H&E, 13 classes) |
| yardimci-nuclick-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | NuClick (tıkla → çekirdek sınırı) sihirbazı | NuClick (click → nuclear boundary) wizard |
| yardimci-hepatosit-sihirbaz.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | Hepatosit bölütleme sihirbazı (Python) | Hepatocyte segmentation wizard (Python) |
| yardimci-mitoz-kongnet.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | KongNet mitoz sonuçlarını içe aktar (GeoJSON) | Import KongNet mitosis results (GeoJSON) |
| yardimci-dogrulama-f1.groovy | Yardımcılar / Utilities | Hücre ve çekirdek tespiti | Cell & nucleus detection | Tespit doğrulama (F1 / IoU) | Detection validation (F1 / IoU) |
| yardimci-goz-karari-kalibrasyon.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Göz kararı kalibrasyon alıştırması | Eyeball calibration exercise |
| yardimci-esik-alan.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Eşik ile alan ölçümü | Threshold area measurement |
| yardimci-sinif-sayim.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Sınıf bazlı hücre sayımı (% dağılım) | Per-class cell count (% distribution) |
| yardimci-ki67-heterojenlik.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Ki-67 heterojenlik ızgarası | Ki-67 heterogeneity grid |
| yardimci-stromal-til.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Stromal TİL yoğunluğu | Stromal TIL density |
| yardimci-alan-pozitiflik.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Alan temelli pozitiflik (% pozitiflik) | Area-based positivity (% positive) |
| yardimci-immun-yogunluk.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | İmmün hücre yoğunluğu (DAB) | Immune cell density (DAB) |
| yardimci-mitoz-phh3.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | PHH3 mitoz kantifikasyonu | PHH3 mitosis quantification |
| yardimci-tumor-tomurcuklanma.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Tümör tomurcuklanma kantifikasyonu (CK / ITBCC) | Tumor budding quantification (CK / ITBCC) |
| yardimci-wsinfer-ozet.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | WSInfer karo özeti (sınıf alanı / %) | WSInfer tile summary (class area / %) |
| yardimci-wsinfer-denetim.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | WSInfer çıkarım denetimi (kurulum · kalibrasyon · sınıf adları) | WSInfer inference audit (install · calibration · class names) |
| yardimci-hotspot-sirali.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Sıralı yoğun odak seçici (top-N) | Ranked hotspot selector (top-N) |
| yardimci-entegre-od.groovy | Yardımcılar / Utilities | Skorlama ve ölçüm | Scoring & quantification | Entegre DAB OD (alan × OD) | Integrated DAB OD (area × OD) |
| yardimci-yapi-uzaklik.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Yapıya uzaklık (sınıra mesafe) | Signed distance to structure boundary |
| yardimci-delaunay-komsuluk.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Delaunay komşuluk özellikleri | Delaunay neighborhood features |
| yardimci-nn-mesafe.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | En yakın komşu mesafesi | Nearest-neighbor distance |
| yardimci-yogunluk-haritasi.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Yoğunluk haritası | Cell density heatmap |
| yardimci-kesisim-alani.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Kesişim alanı (örtüşme) | Intersection area (overlap) |
| yardimci-peritumoral-bant.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Peritümöral bant (halka) | Peritumoral margin band (ring) |
| yardimci-konveks-zarf.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Dış bükey zarf (convex hull) | Convex hull |
| yardimci-capraz-nn-mesafe.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Türler arası en yakın komşu (A→B) | Cross-type nearest neighbor (A→B) |
| yardimci-merkez-cevre.groovy | Yardımcılar / Utilities | Uzamsal analiz | Spatial analysis | Eşit alanlı merkez/çevre | Equal-area center/periphery split |
| yardimci-anotasyon-disa-aktar.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Toplu anotasyon dışa aktarma | Batch annotation export |
| yardimci-anotasyon-ice-aktar.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Toplu anotasyon içe aktarma | Batch annotation import |
| yardimci-karo-disa-aktarma.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Karo (tile) dışa aktarma | Tile export |
| yardimci-omezarr-disa-aktarma.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | OME-Zarr dışa aktarma | OME-Zarr export |
| yardimci-bolge-cikart-sihirbaz.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Görüntü alanı çıkart (Extract Region) | Extract image region |
| yardimci-goruntuden-alan-cikart.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Dikdörtgen seçimlerden görüntü oluştur | Create cropped entries from rectangles |
| yardimci-ozellik-matrisi.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Makine öğrenmesi için öznitelik matrisi | Feature matrix export for ML |
| yardimci-kume-etiketi-iceaktar.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Kümeleme/fenotip etiketlerini içe aktar (TSV) | Import cluster/phenotype labels (TSV) |
| yardimci-tahmin-iceaktar.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Yapay zekâ tahmin maskelerini içe aktar (GeoJSON) | Import AI prediction masks (GeoJSON) |
| yardimci-maske-iceaktar.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Maske görüntüsünü içe aktar | Import raster mask image |
| yardimci-tiatoolbox-maske.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | TIA Toolbox için bölge maskesi | Region mask for TIA Toolbox |
| yardimci-tma-cekirdek-aktarim.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | TMA çekirdek bazlı dışa aktarım | Per-core TMA export |
| yardimci-dicom-svs-sihirbaz.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | DICOM → SVS dönüştürme (DICOMtoSVS) | DICOM → SVS converter (DICOMtoSVS) |
| yardimci-ornek-siniflandirici.groovy | Yardımcılar / Utilities | İçe ve dışa aktarma / veri | Import, export & data | Örnek tümör/stroma sınıflandırıcısını projeye kaydet | Save sample tumor/stroma classifier to project |
| yardimci-python-ortam-yoneticisi.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | Atölye Python ortam yöneticisi | Workshop Python environment manager |
| yardimci-tiatoolbox-bolge-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | TIA Toolbox bölgede çekirdek/mitoz tespiti | TIA Toolbox regional nucleus/mitosis detection |
| yardimci-kaiko-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | Kaiko Midnight sınıflandırıcı sihirbazı | Kaiko Midnight classifier wizard |
| yardimci-spider-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | SPIDER doku sınıflandırıcı sihirbazı | SPIDER tissue classifier wizard |
| yardimci-histoart-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | HistoART artefakt tespiti sihirbazı | HistoART artifact detection wizard |
| yardimci-foundation-model-sihirbaz.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | Temel model hazırlık ve sağlamlık sihirbazı | Foundation model readiness & robustness wizard |
| yardimci-ajan-kopru-denetimi.groovy | Yardımcılar / Utilities | Python köprüleri ve temel modeller | Python bridges & foundation models | Python ve yapay zekâ ajanı köprü denetimi | Python & AI agent bridge audit |
| yardimci-metadata-sihirbaz.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | Kohort üst veri sihirbazı | Cohort slide metadata wizard |
| yardimci-sectra-iceaktar.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | Sectra PACS anotasyon sihirbazı | Sectra PACS annotation wizard |
| yardimci-anonim-sihirbaz.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | WSI anonimleştirme sihirbazı | WSI anonymization wizard |
| yardimci-kohort-ozet-topla.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | Kohort özet toplayıcı (proje tablosu) | Cohort summary collector (project table) |
| yardimci-grandqc-kohort-ozet.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | GrandQC kohort KK özeti | GrandQC cohort QC summary |
| yardimci-kosu-manifesti.groovy | Yardımcılar / Utilities | Klinik ve kohort | Clinical & cohort | İşlem kaydı (provenance JSON) | Run provenance manifest (JSON) |
| yardimci-goruntu-isleme-turu.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | Görüntü işleme kavramları | Image processing concepts tour |
| yardimci-tus-fare-gostergesi.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | Tuş/fare göstergesi (kayıt için) | Keystroke/mouse overlay (for screen recording) |
| yardimci-goruntu-yakala.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | Görüntü yakala (rapor/sunum) | Capture snapshot (report/slides) |
| yardimci-imagej-otsu-anotasyon.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | ImageJ ile otomatik eşik → anotasyon | ImageJ auto-threshold → annotation |
| yardimci-imagej-spline-duzeltme.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | ImageJ ile sınır yumuşat (spline) | ImageJ boundary smoothing (spline) |
| yardimci-dagilim-grafigi.groovy | Yardımcılar / Utilities | Eğitim, sunum ve ImageJ | Education, presentation & ImageJ | Dağılım grafiği (scatter chart) | Scatter chart |

`yardimci-tiatoolbox-sihirbaz.groovy` appears twice (Modüller → Doku tespiti and Yardımcılar → Python köprüleri ve temel modeller). The hint in it uses the Yardımcılar entry, whose label matches the hint text.

## 2. Changed lines

Scripts: plain literals/docstrings were edited in place; strings that were `workshopText(...)` calls were replaced by `@WT:pathN@` tokens (see spec files). Properties: 4 lines (2 per language), outside the generated block.

| file | old (fragment) | new (fragment) |
|---|---|---|
| er-pr-hscore.groovy | (Yardımcılar → Kalibrasyon). | (Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)). |
| nukleer-boya.groovy | (Yardımcılar → Kalibrasyon). | (Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)). |
| hucre-tespiti.groovy | Piksel kalibrasyonu yok; mm² hesaplanamaz (Yardımcılar → Kalibrasyon). | Piksel kalibrasyonu yok; mm² hesaplanamaz (Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)). |
| hucre-tespiti.groovy | lamınızda görmek için: Yardımcılar → Görüntü işleme kavramları. | lamınızda görmek için: Extensions → Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → Görüntü işleme kavramları. |
| tumor-ici-ki67.groovy | (Yardımcılar → Görüntü tipi ayarla).'] | (Yardımcılar → Temel araçlar → Görüntü tipi ayarla).'] |
| tumor-ici-ki67.groovy | menuHint('Extensions → Atölye → Yardımcılar → Görüntü tipi ayarla') | menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla') |
| tumor-ici-ki67.groovy | menuHint('Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)') | menuHint('Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)') |
| model-egit.groovy | Değilse: Yardımcılar → Görüntü tipi ayarla. | Değilse: Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla. |
| model-egit.groovy | Değilse: Yardımcılar → Kalibrasyon. | Değilse: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu). |
| yardimci-boya-kalite-qc.groovy | Atölye → Yardımcılar → Boya kalitesi QC ölçümü] | Atölye → Yardımcılar → Boya ve renk → Boya kalitesi QC ölçümü] |
| yardimci-boya-vektor-sihirbaz.groovy | (gerekirse: Yardımcılar → Görüntü tipi ayarla) | (gerekirse: Yardımcılar → Temel araçlar → Görüntü tipi ayarla) |
| yardimci-boya-vektor-sihirbaz.groovy | Atölye → Yardımcılar → Boya vektörleri sihirbazı] | Atölye → Yardımcılar → Boya ve renk → Boya vektörleri sihirbazı] |
| yardimci-capraz-nn-mesafe.groovy |  *      Çapraz-tip En Yakın Komşu] |  *      Türler arası en yakın komşu (A→B)] |
| yardimci-cellpose-sihirbaz.groovy | Atölye → Yardımcılar → Cellpose hücre/çekirdek tespiti sihirbazı] | Atölye → Yardımcılar → Hücre ve çekirdek tespiti → Cellpose hücre/çekirdek tespiti sihirbazı] |
| yardimci-cellpose-sihirbaz.groovy | ayarlı değil → Yardımcılar → Kalibrasyon) | ayarlı değil → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)) |
| yardimci-dagilim-grafigi.groovy | Atölye → İleri analiz → Dağılım grafiği (scatter chart)] | Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → Dağılım grafiği (scatter chart)] |
| yardimci-dogrulama-f1.groovy | Atölye → Yardımcılar → Tespit doğrulama (F1 / IoU)] | Atölye → Yardımcılar → Hücre ve çekirdek tespiti → Tespit doğrulama (F1 / IoU)] |
| yardimci-doku-tespiti-sihirbaz.groovy | Atölye → Yardımcılar → Doku Tespiti (native)] | Atölye → Modüller → Doku tespiti → Yerleşik (Otsu / ContourTracing)] |
| yardimci-entegre-od.groovy | Skorlama ve ölçüm → Entegre DAB OD] | Skorlama ve ölçüm → Entegre DAB OD (alan × OD)] |
| yardimci-entegre-od.groovy | Kalibrasyon için: Extensions → Atölye → Yardımcılar → Kalibrasyon. | Kalibrasyon için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu). |
| yardimci-esik-alan.groovy | Atölye → Yardımcılar → Eşik ile alan ölçümü] | Atölye → Yardımcılar → Skorlama ve ölçüm → Eşik ile alan ölçümü] |
| yardimci-esik-alan.groovy | [Extensions → Atölye → Yardımcılar → Kalibrasyon (piksel boyutu)] | [Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)] |
| yardimci-esik-ayarla.groovy | Atölye → Yardımcılar → Eşikleri ayarla] | Atölye → Yardımcılar → Temel araçlar → Eşikleri ayarla] |
| yardimci-esik-siniflandirici.groovy | Atölye → Yardımcılar → Doku tespiti → Piksel eşikleyici] | Atölye → Modüller → Doku tespiti → Piksel eşikleyici (Create thresholder)] |
| yardimci-foundation-model-sihirbaz.groovy | Atölye → Yardımcılar → Foundation model hazırlık ve sağlamlık sihirbazı] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → Temel model hazırlık ve sağlamlık sihirbazı] |
| yardimci-foundation-model-sihirbaz.groovy | (Yardımcılar → Kalibrasyon); aksi halde | (Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)); aksi halde |
| yardimci-goruntu-isleme-turu.groovy | Atölye → Yardımcılar → Görüntü işleme kavramları] | Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → Görüntü işleme kavramları] |
| yardimci-goruntu-isleme-turu.groovy | vektörü için: Yardımcılar → Boya vektörleri sihirbazı.' | vektörü için: Yardımcılar → Boya ve renk → Boya vektörleri sihirbazı.' |
| yardimci-goruntu-kunye.groovy | Atölye → Yardımcılar → Görüntü künyesi ve histogram] | Atölye → Yardımcılar → Temel araçlar → Görüntü künyesi ve histogram] |
| yardimci-goruntuden-alan-cikart.groovy | gerekiyorsa: Yardımcılar → Görüntü alanı çıkart. | gerekiyorsa: Yardımcılar → İçe ve dışa aktarma / veri → Görüntü alanı çıkart (Extract Region). |
| yardimci-grandqc-sihirbaz.groovy | GrandQC modeli (doku & artefakt, Python)] | GrandQC modeli (doku ve artefakt, Python)] |
| yardimci-hepatosit-sihirbaz.groovy | Atölye → Yardımcılar → Hücre/çekirdek tespiti → Hepatosit segmentasyonu sihirbazı] | Atölye → Yardımcılar → Hücre ve çekirdek tespiti → Hepatosit bölütleme sihirbazı (Python)] |
| yardimci-hotspot-sirali.groovy | Yardımcılar → Uzamsal analiz → Sıralı Hotspot Seçici] | Yardımcılar → Skorlama ve ölçüm → Sıralı yoğun odak seçici (top-N)] |
| yardimci-hovernext-sihirbaz.groovy | Kurulum: Extensions → Atölye → Yardımcılar →⏎ *   Python köprüleri → Atölye | Kurulum: Extensions → Atölye → Yardımcılar →⏎ *   Python köprüleri ve temel modeller → Atölye |
| yardimci-hovernext-sihirbaz.groovy | Yardımcılar → Temel araçlar → Kalibrasyon ile piksel | Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu) ile piksel |
| yardimci-icc-denetci-sihirbaz.groovy | etmektir (Yardımcılar → Boya vektörleri sihirbazı) | etmektir (Yardımcılar → Boya ve renk → Boya vektörleri sihirbazı) |
| yardimci-icc-denetci-sihirbaz.groovy | Atölye → Yardımcılar → ICC renk profili denetçisi] | Atölye → Yardımcılar → Boya ve renk → ICC renk profili denetçisi] |
| yardimci-icc-denetci-sihirbaz.groovy | (Yardımcılar →\n'⏎    sb << '    Boya vektörleri sihirbazı).\n' | (Yardımcılar → Boya ve renk →\n'⏎    sb << '    Boya vektörleri sihirbazı).\n' |
| yardimci-imagej-otsu-anotasyon.groovy | Atölye → İleri analiz → ImageJ ile otomatik eşik → anotasyon] | Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → ImageJ ile otomatik eşik → anotasyon] |
| yardimci-imagej-otsu-anotasyon.groovy | (Atölye → Yardımcılar → Görüntü tipi ayarla). | (Extensions → Atölye → Yardımcılar → Temel araçlar → Görüntü tipi ayarla). |
| yardimci-imagej-spline-duzeltme.groovy | Atölye → İleri analiz → ImageJ ile sınır yumuşat (spline)] | Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → ImageJ ile sınır yumuşat (spline)] |
| yardimci-instanseg-sihirbaz.groovy | (bkz. Yardımcılar → StarDist⏎ | (bkz. Yardımcılar → Hücre ve çekirdek tespiti → StarDist⏎ |
| yardimci-instanseg-sihirbaz.groovy | Atölye → Yardımcılar → InstanSeg çekirdek/hücre tespiti sihirbazı] | Atölye → Yardımcılar → Hücre ve çekirdek tespiti → InstanSeg çekirdek/hücre tespiti sihirbazı] |
| yardimci-kaiko-sihirbaz.groovy | Atölye → Yardımcılar → Kaiko Midnight sınıflandırıcı sihirbazı] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → Kaiko Midnight sınıflandırıcı sihirbazı] |
| yardimci-karo-disa-aktarma.groovy | Atölye → Yardımcılar → Karo (tile) dışa aktarma] | Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Karo (tile) dışa aktarma] |
| yardimci-kohort-boya-qc.groovy | Atölye → Yardımcılar → Kohort Boya OD Drift] | Atölye → Yardımcılar → Boya ve renk → Kohort boya OD sürüklenme raporu] |
| yardimci-kohort-ozet-topla.groovy | Atölye → Yardımcılar → Kohort → Kohort özet toplayıcı] | Atölye → Yardımcılar → Klinik ve kohort → Kohort özet toplayıcı (proje tablosu)] |
| yardimci-maske-iceaktar.groovy | Atölye → Yardımcılar → Maske görüntüsünü içe aktar] | Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Maske görüntüsünü içe aktar] |
| yardimci-merkez-cevre.groovy | Uzamsal analiz → Eşit-alan merkez/çevre] | Uzamsal analiz → Eşit alanlı merkez/çevre] |
| yardimci-mitoz-atipik-effnet.groovy | Yardımcılar → Python köprüleri → Atölye Python ortam⏎ | Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam⏎ |
| yardimci-mitoz-fcos.groovy | Yardımcılar → Python köprüleri → Atölye Python ortam⏎ | Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam⏎ |
| yardimci-mitoz-karsilastir.groovy | Yardımcılar → Python köprüleri → Atölye Python⏎ | Yardımcılar → Python köprüleri ve temel modeller → Atölye Python⏎ |
| yardimci-mitoz-retinanet.groovy | Yardımcılar → Python⏎ *   köprüleri → Atölye Python ortam yöneticisi | Yardımcılar → Python⏎ *   köprüleri ve temel modeller → Atölye Python ortam yöneticisi |
| yardimci-mitoz-tiatoolbox.groovy | Yardımcılar → Python köprüleri → Atölye Python ortam⏎ | Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam⏎ |
| yardimci-mitoz-fcos.groovy | kalibre değil (Yardımcılar → Kalibrasyon). | kalibre değil (Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)). |
| yardimci-omezarr-disa-aktarma.groovy | Atölye → Yardımcılar → OME-Zarr dışa aktarma] | Atölye → Yardımcılar → İçe ve dışa aktarma / veri → OME-Zarr dışa aktarma] |
| yardimci-ozellik-matrisi.groovy | Atölye → Yardımcılar → Makine öğrenmesi için özellik matrisi] | Atölye → Yardımcılar → İçe ve dışa aktarma / veri → Makine öğrenmesi için öznitelik matrisi] |
| yardimci-python-ortam-yoneticisi.groovy | Atölye → Yardımcılar → Atölye Python ortam yöneticisi] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi] |
| yardimci-sectra-iceaktar.groovy | Atölye → Yardımcılar → Sectra PACS anotasyon sihirbazı] | Atölye → Yardımcılar → Klinik ve kohort → Sectra PACS anotasyon sihirbazı] |
| yardimci-sinif-sayim.groovy | Atölye → Yardımcılar → Sınıf bazlı hücre sayımı (% dağılım)] | Atölye → Yardımcılar → Skorlama ve ölçüm → Sınıf bazlı hücre sayımı (% dağılım)] |
| yardimci-spider-sihirbaz.groovy | Atölye → Yardımcılar → SPIDER doku sınıflandırıcı sihirbazı] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → SPIDER doku sınıflandırıcı sihirbazı] |
| yardimci-stardist-sihirbaz.groovy | Atölye → Yardımcılar → StarDist çekirdek tespiti sihirbazı] | Atölye → Yardımcılar → Hücre ve çekirdek tespiti → StarDist çekirdek tespiti sihirbazı] |
| yardimci-tespitleri-sil.groovy | Atölye → Yardımcılar → Tespitleri sil] | Atölye → Yardımcılar → Temel araçlar → Tespitleri sil] |
| yardimci-tiatoolbox-bolge-sihirbaz.groovy | Atölye → Yardımcılar → TIA Toolbox bölgede çekirdek/mitoz tespiti] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → TIA Toolbox bölgede çekirdek/mitoz tespiti] |
| yardimci-tiatoolbox-maske.groovy | Atölye → Yardımcılar → TIA Toolbox için bölge maskesi] | Atölye → Yardımcılar → İçe ve dışa aktarma / veri → TIA Toolbox için bölge maskesi] |
| yardimci-tiatoolbox-sihirbaz.groovy | Atölye → Yardımcılar → TIA Toolbox boyama normalizasyonu / doku maskesi sihirbazı] | Atölye → Yardımcılar → Python köprüleri ve temel modeller → TIA Toolbox boyama normalizasyonu / doku maskesi sihirbazı] |
| yardimci-tumor-tomurcuklanma.groovy | [Yardımcılar → Kalibrasyon] ile | [Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)] ile |
| yardimci-valis-sihirbaz.groovy | VALIS (Docker/native)] | VALIS (Docker/yerel)] |
| yardimci-wsinfer-denetim.groovy | Skorlama ve ölçüm → WSInfer çıkarım denetimi] | Skorlama ve ölçüm → WSInfer çıkarım denetimi (kurulum · kalibrasyon · sınıf adları)] |
| yardimci-wsinfer-ozet.groovy | Atölye → Yardımcılar → WSInfer karo özeti (sınıf alanı / %)] | Atölye → Yardımcılar → Skorlama ve ölçüm → WSInfer karo özeti (sınıf alanı / %)] |
| yardimci-yapi-uzaklik.groovy | Atölye → Yardımcılar → Yapıya uzaklık (sınıra mesafe)] | Atölye → Yardımcılar → Uzamsal analiz → Yapıya uzaklık (sınıra mesafe)] |
| model-egit.groovy | workshopText(script.1fae35d24240…) "Bu betik Hematoxylin/Eosin renk ayrıştırma kanallarını kullanır; bunun için⏎görüntü tipi B" | Bu betik Hematoxylin/Eosin renk ayrıştırma kanallarını kullanır; bunun için⏎görüntü tipi Brightfield (H&E) olmalı ve boya vektörleri tanımlı |
| model-egit.groovy | workshopText(script.bff255079a29…) "Lamda piksel boyutu (µm/px) tanımlı değil; sınıflandırıcı çözünürlüğü hesaplanamaz.⏎⏎Çözüm" | Lamda piksel boyutu (µm/px) tanımlı değil; sınıflandırıcı çözünürlüğü hesaplanamaz.⏎⏎Çözüm: Extensions → Atölye → Yardımcılar → Temel araçla |
| tumor-stroma.groovy | workshopText(script.c6967ae055af…) "Örnek modeli kurmak için: Extensions → Atölye → Yardımcılar →⏎"Örnek tümör/stroma sınıflan" | Örnek modeli kurmak için: Extensions → Atölye → Yardımcılar → İçe ve dışa aktarma / veri →⏎"Örnek tümör/stroma sınıflandırıcısını projeye ka |
| tumor-stroma.groovy | workshopText(script.3389ec6facef…) "Sihirbaz için: Extensions → Atölye → Modüller →⏎"Tümör/Stroma sihirbazı (model kur/eğit)"." | Sihirbaz için: Extensions → Atölye → Modüller → Tümör/Stroma →⏎"Tümör/Stroma sihirbazı". |
| yardimci-boya-vektor-sihirbaz.groovy | workshopText(script.94f0d15b2170…) "Bu görüntüde renk-dekonvolüsyon (boya) vektörleri yok.⏎⏎Olası neden: görüntü tipi brightfi" | Bu görüntüde renk-dekonvolüsyon (boya) vektörleri yok.⏎⏎Olası neden: görüntü tipi brightfield değil (örn. floresan/diğer).⏎⏎Çözüm:⏎  • [Exte |
| yardimci-capraz-nn-mesafe.groovy | workshopText(script.5dad5b2ba71a…) "Lamda piksel boyutu (µm) tanımlı değil.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atöl" | Lamda piksel boyutu (µm) tanımlı değil.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (pi |
| yardimci-nn-mesafe.groovy | workshopText(script.5dad5b2ba71a…) "Lamda piksel boyutu (µm) tanımlı değil.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atöl" | Lamda piksel boyutu (µm) tanımlı değil.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (pi |
| yardimci-dagilim-grafigi.groovy | workshopText(script.a84140786522…) "Çizilebilir ölçüm bulunamadı.⏎⏎Önce ölçümlü tespitler oluşturun (Hücre tespiti / Nükleer b" | Çizilebilir ölçüm bulunamadı.⏎⏎Önce ölçümlü tespitler oluşturun (Hücre tespiti / Nükleer boya / Sitoplazmik boya /⏎Tümör içi Ki-67 modülleri |
| yardimci-goruntu-isleme-turu.groovy | workshopText(script.9bcabe556ddc…) "Bu görüntüde renk dekonvolüsyonu (boya vektörü) tanımlı değil — büyük olasılıkla brightfie" | Bu görüntüde renk dekonvolüsyonu (boya vektörü) tanımlı değil — büyük olasılıkla brightfield değil. Görüntü tipini ayarlayın (Yardımcılar →  |
| yardimci-hovernext-sihirbaz.groovy | workshopText(script.3ae42d7aeda3…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "HoVer-NeXt — çekirdek  |
| yardimci-kesisim-alani.groovy | workshopText(script.542aac19e376…) "Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak hesaplanamaz.⏎⏎Piksel boyutunu aya" | Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak hesaplanamaz.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → T |
| yardimci-merkez-cevre.groovy | workshopText(script.821202426064…) "Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak üretilemez.⏎⏎Piksel boyutunu ayarl" | Lamda piksel boyutu (µm) tanımlı değil; alan µm² olarak üretilemez.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → Tem |
| yardimci-peritumoral-bant.groovy | workshopText(script.42db62f0c526…) "Lamda piksel boyutu (µm) tanımlı değil; bant µm cinsinden üretilemez.⏎⏎Piksel boyutunu aya" | Lamda piksel boyutu (µm) tanımlı değil; bant µm cinsinden üretilemez.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar → T |
| yardimci-yapi-uzaklik.groovy | workshopText(script.f515091dc00d…) "Lamda piksel boyutu (µm) tanımlı değil; mesafe µm olarak hesaplanamaz.⏎⏎Piksel boyutunu ay" | Lamda piksel boyutu (µm) tanımlı değil; mesafe µm olarak hesaplanamaz.⏎⏎Piksel boyutunu ayarlamak için: Extensions → Atölye → Yardımcılar →  |
| yardimci-mitoz-atipik-effnet.groovy | workshopText(script.713f4ab40a33…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "MIDOG25 EffNetV2 — ati |
| yardimci-mitoz-atipik-sanofi.groovy | workshopText(script.3046d905f7be…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "Sanofi EFTD — atipik s |
| yardimci-mitoz-fcos.groovy | workshopText(script.697752520bcb…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "MIDOG25 FCOS — mitoz d |
| yardimci-mitoz-retinanet.groovy | workshopText(script.d0048b8429e8…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "MIDOG DA-RetinaNet (es |
| yardimci-mitoz-tiatoolbox.groovy | workshopText(script.06d8204c0ed6…) "⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri → Atölye Python ortam yöne" | ⏎⏎Kurulum: Extensions → Atölye → Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi → "TIA Toolbox — bölge mo |
| yardimci-mitoz-tiatoolbox.groovy | workshopText(script.6542cc6fbe0a…) "Model: Bu modül H&E mitoz için sabit "KongNet MIDOG" (MIDOG 2025 birincisi) kullanır — TIA" | Model: Bu modül H&E mitoz için sabit "KongNet MIDOG" (MIDOG 2025 birincisi) kullanır — TIA Toolbox'ta mitoza uygun TEK model. Çekirdek/hücre |
| yardimci-valis-sihirbaz.groovy | workshopText(script.efa21cc956ea…) "Menüden açın: Yardımcılar → Python köprüleri → Atölye Python ortam yöneticisi" | Menüden açın: Yardımcılar → Python köprüleri ve temel modeller → Atölye Python ortam yöneticisi |
| strings_tr.properties cell.noScale | (Yardımcılar → Kalibrasyon). | (Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon (piksel boyutu)). |
| strings_tr.properties cell.instructions | Yardımcılar → Görüntü işleme kavramları. | Extensions → Atölye → Yardımcılar → Eğitim, sunum ve ImageJ → Görüntü işleme kavramları. |
| strings_en.properties cell.noScale | Open Utilities → Basic tools → Calibration (pixel size). | Open Extensions → Atölye → Utilities → Basic tools → Calibration (pixel size). |
| strings_en.properties cell.instructions | open Utilities → Image-processing concepts. | open Extensions → Atölye → Utilities → Education, presentation & ImageJ → Image processing concepts tour. |

The Turkish fallbacks in hucre-tespiti.groovy (`cell.noScale`, `cell.instructions`) are byte-identical to the new Turkish property values.

## 3. Hints found but NOT changed

- Already complete and correct (full path, real labels, both languages): `script.e9d070cff4f1` (er-pr-hscore, membran-boya, nukleer-boya, sitoplazmik-boya), `script.9641266ba42d` (23 uses), `script.afa546e7967e`, `script.df5870effef8`, `script.6c014450748f` (Mitoz modelleri listesi, 6 uses), `script.66586705b08c` (immun-yogunluk, stromal-til), `script.0c7b436d79a1`, and the docstring/menuHint paths in arayuz-turu, quantum-ctcf, sihirbaz, anotasyon-disa/ice-aktar, anotasyon-qc, bolge-cikart, anonim, ajan-kopru, dicom-svs, entegre-od, goz-karari, goruntuden-alan-cikart, grandqc-kohort-ozet, histoart, konveks-zarf, kosu-manifesti, kesisim-alani, metadata, mitoz-kongnet, mitoz-merkez, nuclick, peritumoral-bant, wsinfer.
- Start at `Yardımcılar →` without the `Extensions → Atölye →` root but name the group and tool: `script.194faa563e2b` (dicom-svs), the ajan-kopru line 318 (`[Yardımcılar → Klinik ve kohort → WSI anonimleştirme sihirbazı → Denetle]`), `script.efa21cc956ea` after fix (valis), and the `Yardımcılar → Temel araçlar → …` error strings. They meet the "at least Yardımcılar → group → tool" bar.
- yardimci-esik-ayarla.groovy:830 `(Yardımcılar → Boya ve renk)`: names the group, and the tool ("Boya vektörleri sihirbazı") is named just before it in the sentence. It is a wrapped call, so changing it would need a spec; left as is. Uncertain; say if you want it expanded.
- yardimci-hovernext-sihirbaz.groovy:20 and :96 `Modüller → Mitoz tespiti dedektörleri`: a valid path to the submenu, no single tool is meant.
- Code comments (not user-visible), left alone: hovernext:103 (`Mitoz tespiti → Modelleri karşılaştır sihirbazı`, real label is `Modelleri karşılaştır (aynı bölge)`), tiatoolbox-sihirbaz:109 (`Doku tespiti → TIA Toolbox maskesi`, real label `TIA Toolbox maskesi (Otsu/morfoloji, Python)`), esik-ayarla:446, bolge-cikart:65, ajan-kopru:109 (section headings, not paths).
- `Ekler → Görüntü Hizalama …` (hizalama-aktarim, senkron-izgara, valis, warpy): these point to the website appendix chapter, not the menu.
- Paths in other extensions' menus (`Extensions → WSInfer`, `Extensions → Manage extensions`, `Extensions → TIAToolbox → Install Python runtime…`, `Extensions → MCP server`, `Extensions → Py4J`, `Extensions → InstanSeg`), QuPath's own menus, and the generic `(Extensions → Atölye)` in mitoz-merkez:122.
- script-core.json and the generated properties block: untouched, as instructed. The English text of every token is derived from the current script-core.json English, so the build tool that regenerates them must read the spec files.

## 4. Labels in hints that matched no real menu entry

Corrected where a real entry exists (old to real): `Doku Tespiti (native)` to `Yerleşik (Otsu / ContourTracing)` (and its root was wrong: it is under Modüller, not Yardımcılar); `Yardımcılar → Doku tespiti → Piksel eşikleyici` (root wrong, it is under Modüller → Doku tespiti); `İleri analiz → …` (group is empty and disabled; the 3 tools are under Yardımcılar → Eğitim, sunum ve ImageJ); `Çapraz-tip En Yakın Komşu`; `Sıralı Hotspot Seçici` (was also placed under Uzamsal analiz, real group is Skorlama ve ölçüm); `Hepatosit segmentasyonu sihirbazı` and group `Hücre/çekirdek tespiti`; `Kohort → …` (group is Klinik ve kohort); `Kohort Boya OD Drift`; `Eşit-alan merkez/çevre`; `Foundation model hazırlık …`; `özellik matrisi`; `VALIS (Docker/native)`; `GrandQC modeli (doku & artefakt …)`; EN `Extensions → Workshop → …` in tumor-stroma (root is Atölye); `"Tümör/Stroma sihirbazı (model kur/eğit)"` and EN `Tumour/Stroma wizard (build/train model)`; EN `Save example tumour/stroma classifier …`; `Python köprüleri` (group is `Python köprüleri ve temel modeller`).

Still matching no menu entry (left, with reason): the quoted names `"TIA Toolbox — bölge modelleri"`, `"MIDOG25 FCOS — mitoz dedektörü"`, `"MIDOG25 EffNetV2 — atipik sınıflandırıcı"`, `"Sanofi EFTD — atipik sınıflandırıcı"`, `"MIDOG DA-RetinaNet (eski, CPU)"`, `"HoVer-NeXt — çekirdek segmentasyon + 7 sınıf"`, `"DICOMtoSVS"`, `"histoart"`, `"nuclick"`. These are entries in the Atölye Python ortam yöneticisi window, not menu items, so they are right where they stand. The one exception (mitoz-tiatoolbox hint that pointed at the env name as if it were a menu entry) was corrected to the real menu label `TIA Toolbox bölgede çekirdek/mitoz tespiti`.
