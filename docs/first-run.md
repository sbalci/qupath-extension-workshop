# İlk çalışma / First run

## Türkçe — 5–10 dakika

**Hedef:** sentetik görüntüde kapalı bir alan seçip hücre tespitinin konturlarını ve sayısını görün. Patologlar için bu, bir ölçümün hangi alanı kapsadığını denetleme alıştırmasıdır. Bilgisayar bilimciler için görüntü → alan anotasyonu → nesne → ölçüm zinciridir. Ön koşul: QuPath 0.6.0+ ve atölye eklentisi. **Terminal (komut penceresi)**, komut yazıp Enter ile çalıştırdığınız penceredir; bu örnekte terminal veya Python gerekmez ([Microsoft](https://learn.microsoft.com/en-us/windows/terminal/)).

1. [Sentetik örneği açın](../assets/examples/synthetic-nuclei.png). GitHub'da görüntünün sağ üstündeki **Download raw file** düğmesiyle dosyayı indirin. Dosya çoğu bilgisayarda **İndirilenler (Downloads)** klasörüne iner. Dosya adının `.png` ile bittiğini kontrol edin; web sayfasını `.html` olarak kaydetmeyin.
2. PNG dosyasını QuPath penceresine sürükleyin. Görüntü tipi sorulursa **Brightfield (H&E)** seçin. Beyaz zemin üzerinde 10 × 10 mavi daire görmelisiniz. Bunlar gerçek doku veya hasta hücresi değildir.
3. PNG fiziksel ölçek taşımaz. **Extensions → Atölye → Yardımcılar → Temel araçlar → Kalibrasyon** öğesini açın. **Doğrudan değer gir** yönteminde piksel boyutunu **0.5 µm/piksel** yapın (pencere ondalık nokta kullanır; virgül de kabul edilir) ve yalnız açık görüntüye uygulayın. Bu değer yalnız bu sentetik örnek için seçilmiştir.
4. **Rectangle** aracını seçin veya **R** tuşuna basın. Dairelerin tamamını içine alan bir dikdörtgen çizin. Kenarı sarı olmalı. Bu kapalı alan *ilgi bölgesidir (ROI)*; nokta veya çizgi aynı işi yapmaz.
5. **Extensions → Atölye → Modüller → Hücre tespiti** öğesini açın. Varsayılan eşik **0.1**, sigma **1.5 µm**, hücre genişletme **5 µm** ile **Çalıştır** düğmesine basın.
6. Dairelerin etrafında ayrı konturlar ve sonuç penceresinde toplam nesne sayısı görmelisiniz. Beklenen çizim sayısı **100**'dür. Konturları görüntüyle birlikte inceleyin; bu örnek gerçek doku başarımını ölçmez. **Measure → Show detection measurements** ile her tespitin bir satır oluşturduğunu görün.

**Beklenen sonuç:** 100 ayrı sentetik çekirdek çizimi, tespit konturları ve nesne ölçüm tablosu. Çizimin kaynağı ve SHA-256 özeti [örnek manifestindedir](../assets/examples/synthetic-nuclei.json).

**Takıldıysanız:** Dosya açılmıyorsa `.png` yerine GitHub sayfasını indirmiş olabilirsiniz; 1. adımı tekrarlayın. Ölçek uyarısında 3. adımı kontrol edin. “Alan seçili değil” uyarısında **R** ile kapalı alan çizin ve seçin. Sayı farklıysa önceki ayarlarınızı not edin, bu örnekteki üç değeri kullanın ve alanın tüm daireleri kapsadığını denetleyin. Başka bir görüntüde bu örneğin ölçeğini kullanmayın.

## English — 5–10 minutes

**Goal:** select a closed region on a synthetic image and inspect detection contours and the object count. For pathologists, this exercises checking which region a measurement covers. For computer scientists, it follows image → annotation → object → measurement. Prerequisites: QuPath 0.6.0+ and the workshop extension. A **terminal** is the window where you enter commands; no terminal or Python is needed for this example ([Microsoft](https://learn.microsoft.com/en-us/windows/terminal/)).

1. [Open the synthetic image](../assets/examples/synthetic-nuclei.png). On GitHub, click **Download raw file** at the upper right of the image. On most computers the file lands in the **Downloads** folder. Check that the saved filename ends in `.png`; do not save the web page as `.html`.
2. Drag the PNG into QuPath. Choose **Brightfield (H&E)** if asked for an image type. You should see 10 × 10 blue circles on white. They are synthetic drawings, with no tissue or patient data.
3. PNG carries no physical scale. Open **Extensions → Atölye → Utilities → Basic tools → Calibration** (the root menu is named *Atölye*, "Workshop", in both languages). Choose **Direct value ▶**, enter **0.5 µm/pixel** on the **Enter direct value** screen, and apply it only to the open image. This scale was chosen only for this example. In an older build whose window is Turkish, these labels are **Doğrudan değer ▶** and **Doğrudan değer gir**.
4. Select **Rectangle**, or press **R**, and draw a rectangle enclosing all circles. Its border should be yellow. This closed *region of interest (ROI)* differs from a point or line annotation.
5. Open **Extensions → Atölye → Modules → Cell detection**. Use threshold **0.1**, sigma **1.5 µm** and cell expansion **5 µm**, then click **Run**. In older builds whose script window is Turkish, the button is **Çalıştır**.
6. Look for separate contours around the circles and a total object count in the results window. The drawing contains **100** objects. Inspect the contours alongside the image; this example does not measure performance on real tissue. Open **Measure → Show detection measurements** to see one row per detection.

**Expected result:** 100 synthetic nuclear drawings, detection contours and an object measurement table. The [manifest](../assets/examples/synthetic-nuclei.json) records provenance and SHA-256.

**If you get stuck:** If the file will not open, download the raw `.png` rather than the GitHub HTML page. For a scale warning, repeat step 3. For “no selected region”, draw and select a closed rectangle with **R**. If the count differs, note your previous settings, use the three values above and confirm that the rectangle encloses every circle. Do not reuse this example's scale for another image.

The small image is generated by `tools/build-synthetic-example.py`, released as CC0, and can be included in an offline workshop folder. No external model or download is needed after saving it.
