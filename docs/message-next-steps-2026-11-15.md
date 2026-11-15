# Messages report

## Changed messages

| script:line | old | new TR | new EN | shown where |
|---|---|---|---|---|
| hucre-tespiti:112 | (log only / none) | Görüntü tipi Floresan | Image type is Fluorescence | see below |
| hucre-tespiti:113 | (log only / none) | Bu modül parlak alan (H&E ya da İHK) lamları içindir; bu lamın görüntü tipi Floresan olduğundan tip değiştirilmedi ve hücre tespiti çalışmayabilir. Parlak alan bir lam açın. Lam gerçekten parlak alansa sol paneldeki Image sekmesinde Image type değerini "Brightfield (H&E)" ya da "Brightfield (H-DAB)" yapıp aracı yeniden çalıştırın. | This module is for brightfield (H&E or IHC) slides; because this slide's image type is Fluorescence, the type was not changed and cell detection may not work. Open a brightfield slide. If the slide really is brightfield, set Image type to "Brightfield (H&E)" or "Brightfield (H-DAB)" in the Image tab on the left and run the tool again. | see below |
| membran-boya:463 | (log only / none) | Cellpose çalıştırılamadı | Cellpose could not run | see below |
| membran-boya:462 | (log only / none) | Cellpose çalıştırılamadı; hücre bazlı tespit Watershed yöntemiyle sürdürüldü (sonuç metninde dedektör adı yazar), piksel tabanlı H-score etkilenmedi. Cellpose'u kullanmak için ortamı denetleyin: Extensions → Atölye → Yardımcılar → Hücre ve çekirdek tespiti → Cellpose hücre/çekirdek tespiti sihirbazı.
Teknik ayrıntı: %s | Cellpose could not run; cell-based detection continued with the Watershed method (the detector name appears in the result text), and the pixel-based H-score is unaffected. To use Cellpose, check its environment: Extensions → Atölye → Utilities → Cell & nucleus detection → Cellpose cell/nucleus detection wizard.
Technical detail: %s | see below |
| membran-boya:510 | (log only / none) | Hücre bazlı tespit atlandı | Cell-based detection skipped | see below |
| membran-boya:509 | (log only / none) | Hücre bazlı tespit atlandı; piksel tabanlı H-score yine de hesaplandı ve geçerlidir. Hücre bazlı karşılaştırma gerekiyorsa ortamı denetleyin (Extensions → Atölye → Ortam kontrolü…) ve aracı yeniden çalıştırın.
Teknik ayrıntı: %s | Cell-based detection was skipped; the pixel-based H-score was still calculated and is valid. If you need the cell-based comparison, check the environment (Extensions → Atölye → Environment check…) and run the tool again.
Technical detail: %s | see below |
| quantum-ctcf:77 | Alt betik hatası (${name}): ${err} (log only) | Sihirbazın yardımcı adımlarından biri çalışırken hata verdi; sihirbazın kendisi açık kalır.
Adımı kendi menü girişinden çalıştırın: %s
Ardından sihirbazda "Yenile"ye basın; durum değişmezse sihirbazı kapatıp yeniden açın.

Teknik ayrıntı: %s | One of the wizard's helper steps failed; the wizard itself stays open.
Run that step from its own menu entry: %s
Then press "Refresh" in the wizard; if the status does not change, close and reopen the wizard.

Technical detail: %s | error dialog (runLater) when a wizard helper step throws; log line kept |
| quantum-ctcf:267 | (log only / none) | StarDist yüklü değil | StarDist is not installed | see below |
| quantum-ctcf:267 | (log only / none) | StarDist eklentisi yüklü olmadığı için klasik Watershed hücre tespiti kullanıldı; sonuçlar StarDist ile aynı olmaz. StarDist'i kurmak için Extensions → Atölye → Ortam kontrolü… penceresine ve kaynaklar sayfasındaki ileri kurulumlar bölümüne bakın, sonra aracı yeniden çalıştırın. | Because the StarDist extension is not installed, classic Watershed cell detection was used; results will not match StarDist. To install StarDist, see Extensions → Atölye → Environment check… and the advanced-installs section of the resources page, then run the tool again. | see below |
| sihirbaz:90 | Alt betik hatası (${name}): ${err} (log only) | Sihirbazın yardımcı adımlarından biri çalışırken hata verdi; sihirbazın kendisi açık kalır.
Adımı kendi menü girişinden çalıştırın: %s
Ardından sihirbazda "Yenile"ye basın; durum değişmezse sihirbazı kapatıp yeniden açın.

Teknik ayrıntı: %s | One of the wizard's helper steps failed; the wizard itself stays open.
Run that step from its own menu entry: %s
Then press "Refresh" in the wizard; if the status does not change, close and reopen the wizard.

Technical detail: %s | error dialog (runLater) when a wizard helper step throws; log line kept |
| tumor-ici-ki67:63 | Alt betik hatası (${name}): ${err} (log only) | Sihirbazın yardımcı adımlarından biri çalışırken hata verdi; sihirbazın kendisi açık kalır.
Adımı kendi menü girişinden çalıştırın: %s
Ardından sihirbazda "Yenile"ye basın; durum değişmezse sihirbazı kapatıp yeniden açın.

Teknik ayrıntı: %s | One of the wizard's helper steps failed; the wizard itself stays open.
Run that step from its own menu entry: %s
Then press "Refresh" in the wizard; if the status does not change, close and reopen the wizard.

Technical detail: %s | error dialog (runLater) when a wizard helper step throws; log line kept |
| tumor-stroma:132 | Alt betik hatası (${name}): ${err} (log only) | Bir yardımcı adım çalışırken hata verdi.
Adımı kendi menü girişinden çalıştırın: %s
Ardından Tümör/Stroma ölçümünü yeniden başlatın.

Teknik ayrıntı: %s | A helper step failed.
Run that step from its own menu entry: %s
Then start the Tumor/Stroma measurement again.

Technical detail: %s | error dialog (runLater) when a wizard helper step throws; log line kept |
| veri-aktarma:660 | (log only / none) | ⚠ Seçtiğiniz kayıt yeri bulunamadı (%s); dosyalar proje varsayılan klasörüne yazıldı: %s
  Başka bir klasör istiyorsanız aracı yeniden çalıştırıp var olan bir klasör seçin. | ⚠ The location you chose was not found (%s); the files were written to the project's default folder: %s
  If you want a different folder, run the tool again and pick a folder that exists. | see below |
| yardimci-grandqc-sihirbaz:501 | GeoJSON okunamadı:
 + raw exception | GeoJSON dosyası okunamadı. Dosyanın GrandQC'nin ürettiği geçerli bir .geojson olduğunu ve başka bir programda açık olmadığını denetleyin; gerekirse GrandQC adımını yeniden çalıştırın.

Teknik ayrıntı: %s | The GeoJSON file could not be read. Check that the file is a valid .geojson produced by GrandQC and is not open in another program; if needed, run the GrandQC step again.

Technical detail: %s | wizard ERROR state / returned error text |
| yardimci-hepatosit-sihirbaz:230 | GeoJSON okunamadı:
 + raw exception | GeoJSON dosyası okunamadı. Aracın Python adımı çıktı dosyasını eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | The GeoJSON file could not be read. The tool's Python step may not have written the output file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state / returned error text |
| yardimci-histoart-sihirbaz:473 | tiles.json okunamadı:
 + raw | tiles.json okunamadı. Python adımı bu dosyayı eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | Could not read tiles.json. The Python step may not have written this file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-histoart-sihirbaz:494 | predictions.json okunamadı:
 + raw | predictions.json okunamadı. Python adımı bu dosyayı eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | Could not read predictions.json. The Python step may not have written this file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-hovernext-sihirbaz:299 | Bölge sınır dışı ya da boş. | Seçili bölge görüntünün dışında kalıyor ya da alanı yok. Doku üzerine kapalı bir bölge çizin (dikdörtgen, çokgen ya da fırça aracıyla), seçin (sınırı sarı görünsün) ve yeniden çalıştırın. | The selected region lies outside the image or has no area. Draw a closed region on tissue (with the rectangle, polygon or brush tool), select it so its border is yellow, and run again. | wizard ERROR state |
| yardimci-hovernext-sihirbaz:309 | Bölge okunamadı (readRegion null döndü). | Seçili bölgenin pikselleri okunamadı. Bölgeyi daha küçük çizip yeniden çalıştırın; sürerse görüntü dosyasına erişilebildiğini denetleyin (taşınmış dosya, bağlantısı kopmuş ağ sürücüsü).

Teknik ayrıntı: readRegion null döndü. | The pixels of the selected region could not be read. Draw a smaller region and run again; if it persists, check that the image file is reachable (moved file, disconnected network drive).

Technical detail: readRegion returned null. | wizard ERROR state |
| yardimci-hovernext-sihirbaz:362 | GeoJSON ayrıştırılamadı: + raw exception | GeoJSON çıktısı ayrıştırılamadı. HoVer-NeXt adımı çıktı dosyasını eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | The GeoJSON output could not be parsed. The HoVer-NeXt step may not have written the output file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-image-type:261 | (log only / none) | Görüntü tipi açık lamda değişti ama diske kaydedilemedi. Değişikliği kalıcı yapmak için QuPath ana penceresinde Ctrl+S ile kaydedin; kaydetmezseniz lamı yeniden açtığınızda eski tip geri gelebilir.
Teknik ayrıntı: %s | The image type was changed on the open slide but could not be saved to disk. To make the change permanent, press Ctrl+S in the main QuPath window; if you do not, the old type may return when you reopen the slide.
Technical detail: %s | see below |
| yardimci-kaiko-sihirbaz:408 | predictions.json okunamadı:
 + raw | predictions.json okunamadı. Python adımı bu dosyayı eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | Could not read predictions.json. The Python step may not have written this file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-mitoz-atipik-sanofi:201 | Bölge sınır dışı ya da boş. | Seçili bölge görüntünün dışında kalıyor ya da alanı yok. Doku üzerine kapalı bir bölge çizin (dikdörtgen, çokgen ya da fırça aracıyla), seçin (sınırı sarı görünsün) ve yeniden çalıştırın. | The selected region lies outside the image or has no area. Draw a closed region on tissue (with the rectangle, polygon or brush tool), select it so its border is yellow, and run again. | wizard ERROR state |
| yardimci-mitoz-atipik-sanofi:207 | Bölge okunamadı (readRegion null döndü). | Seçili bölgenin pikselleri okunamadı. Bölgeyi daha küçük çizip yeniden çalıştırın; sürerse görüntü dosyasına erişilebildiğini denetleyin (taşınmış dosya, bağlantısı kopmuş ağ sürücüsü).

Teknik ayrıntı: readRegion null döndü. | The pixels of the selected region could not be read. Draw a smaller region and run again; if it persists, check that the image file is reachable (moved file, disconnected network drive).

Technical detail: readRegion returned null. | wizard ERROR state |
| yardimci-mitoz-fcos:328 | Bölge sınır dışı ya da boş. | Seçili bölge görüntünün dışında kalıyor ya da alanı yok. Doku üzerine kapalı bir bölge çizin (dikdörtgen, çokgen ya da fırça aracıyla), seçin (sınırı sarı görünsün) ve yeniden çalıştırın. | The selected region lies outside the image or has no area. Draw a closed region on tissue (with the rectangle, polygon or brush tool), select it so its border is yellow, and run again. | wizard ERROR state |
| yardimci-mitoz-fcos:341 | Bölge okunamadı (readRegion null döndü). | Seçili bölgenin pikselleri okunamadı. Bölgeyi daha küçük çizip yeniden çalıştırın; sürerse görüntü dosyasına erişilebildiğini denetleyin (taşınmış dosya, bağlantısı kopmuş ağ sürücüsü).

Teknik ayrıntı: readRegion null döndü. | The pixels of the selected region could not be read. Draw a smaller region and run again; if it persists, check that the image file is reachable (moved file, disconnected network drive).

Technical detail: readRegion returned null. | wizard ERROR state |
| yardimci-mitoz-karsilastir:374 | Bölge sınır dışı ya da boş. | Seçili bölge görüntünün dışında kalıyor ya da alanı yok. Doku üzerine kapalı bir bölge çizin (dikdörtgen, çokgen ya da fırça aracıyla), seçin (sınırı sarı görünsün) ve yeniden çalıştırın. | The selected region lies outside the image or has no area. Draw a closed region on tissue (with the rectangle, polygon or brush tool), select it so its border is yellow, and run again. | wizard ERROR state |
| yardimci-mitoz-karsilastir:383 | Bölge okunamadı (readRegion null döndü). | Seçili bölgenin pikselleri okunamadı. Bölgeyi daha küçük çizip yeniden çalıştırın; sürerse görüntü dosyasına erişilebildiğini denetleyin (taşınmış dosya, bağlantısı kopmuş ağ sürücüsü).

Teknik ayrıntı: readRegion null döndü. | The pixels of the selected region could not be read. Draw a smaller region and run again; if it persists, check that the image file is reachable (moved file, disconnected network drive).

Technical detail: readRegion returned null. | wizard ERROR state |
| yardimci-mitoz-kongnet:287 | Dosya ayrıştırılamadı:

 + raw exception | Dosya ayrıştırılamadı. Dosyanın geçerli bir GeoJSON olduğunu ve başka bir programda açık olmadığını denetleyin; dosyayı üreten araçtan yeniden dışa aktarıp tekrar deneyin.

Teknik ayrıntı: %s | The file could not be parsed. Check that it is a valid GeoJSON and is not open in another program; export it again from the tool that produced it and retry.

Technical detail: %s | error dialog body (existing) |
| yardimci-mitoz-retinanet:276 | Bölge sınır dışı ya da boş. | Seçili bölge görüntünün dışında kalıyor ya da alanı yok. Doku üzerine kapalı bir bölge çizin (dikdörtgen, çokgen ya da fırça aracıyla), seçin (sınırı sarı görünsün) ve yeniden çalıştırın. | The selected region lies outside the image or has no area. Draw a closed region on tissue (with the rectangle, polygon or brush tool), select it so its border is yellow, and run again. | wizard ERROR state |
| yardimci-mitoz-retinanet:285 | Bölge okunamadı (readRegion null döndü). | Seçili bölgenin pikselleri okunamadı. Bölgeyi daha küçük çizip yeniden çalıştırın; sürerse görüntü dosyasına erişilebildiğini denetleyin (taşınmış dosya, bağlantısı kopmuş ağ sürücüsü).

Teknik ayrıntı: readRegion null döndü. | The pixels of the selected region could not be read. Draw a smaller region and run again; if it persists, check that the image file is reachable (moved file, disconnected network drive).

Technical detail: readRegion returned null. | wizard ERROR state |
| yardimci-nuclick-sihirbaz:470 | predictions.json okunamadı:
 + raw | predictions.json okunamadı. Python adımı bu dosyayı eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | Could not read predictions.json. The Python step may not have written this file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-ozellik-matrisi:424 | (log only / none) | Açık lam diske kaydedilemedi; matris o lamın son kayıtlı halinden üretildi. Güncel hali için QuPath ana penceresinde Ctrl+S ile kaydedin ve aracı yeniden çalıştırın.
Teknik ayrıntı: %s | The open slide could not be saved to disk; the matrix was built from that slide's last saved state. For the current state, press Ctrl+S in the main QuPath window and run the tool again.
Technical detail: %s | see below |
| yardimci-sectra-iceaktar:329 | GeoJSON okunamadı:
 + raw exception | GeoJSON dosyası okunamadı. Seçtiğiniz dosyanın bozulmamış bir .geojson (Sectra GSPS dışa aktarımı) olduğunu ve başka bir programda açık olmadığını denetleyin; dosya bozuksa dışa aktarmayı yeniden yapıp tekrar deneyin.

Teknik ayrıntı: %s | The GeoJSON file could not be read. Check that the file you chose is an intact .geojson (a Sectra GSPS export) and is not open in another program; if the file is damaged, export it again and retry.

Technical detail: %s | wizard ERROR state / returned error text |
| yardimci-spider-sihirbaz:436 | predictions.json okunamadı:
 + raw | predictions.json okunamadı. Python adımı bu dosyayı eksiksiz yazmamış olabilir: çalışma günlüğünü ve çıktı klasörünü denetleyin, sonra sihirbazda çalıştırmayı yeniden yapın.

Teknik ayrıntı: %s | Could not read predictions.json. The Python step may not have written this file completely: check the run log and the output folder, then run again from the wizard.

Technical detail: %s | wizard ERROR state |
| yardimci-tahmin-iceaktar:248 | Dosya ayrıştırılamadı:

 + raw exception | Dosya ayrıştırılamadı. Dosyanın geçerli bir GeoJSON olduğunu ve başka bir programda açık olmadığını denetleyin; dosyayı üreten araçtan yeniden dışa aktarıp tekrar deneyin.

Teknik ayrıntı: %s | The file could not be parsed. Check that it is a valid GeoJSON and is not open in another program; export it again from the tool that produced it and retry.

Technical detail: %s | error dialog body (existing) |
| yardimci-valis-sihirbaz:1848 | GeoJSON okunamadı:
 + raw exception | Aracın ürettiği hizalanmış GeoJSON dosyası okunamadı. Dosyanın tam yazıldığını ve başka bir programda açık olmadığını denetleyin; gerekirse sihirbazda hizalama adımını yeniden çalıştırın.

Teknik ayrıntı: %s | The aligned GeoJSON file produced by the tool could not be read. Check that the file was written completely and is not open in another program; if needed, run the alignment step again in the wizard.

Technical detail: %s | wizard ERROR state / returned error text |

Step-path tokens (msgStep*) and msgSubstepTitle are the menu-path/title pieces of the Alt betik hatası dialog (4 scripts).

## Log-only warnings made visible (log line kept)

- hucre-tespiti: FLORESAN warning -> non-blocking warning notification (GUI only), keys cell.msg.fluorTitle / cell.msg.fluorBody
- membran-boya: Cellpose failed -> Watershed fallback -> warning notification with reason (runLater); Hücre bazlı tespit atlandı -> warning notification with reason
- quantum-ctcf: StarDist missing -> Watershed fallback -> warning notification (runLater; GUI only)
- yardimci-image-type: ImageData could not be saved -> line in result window (Ctrl+S advice)
- yardimci-ozellik-matrisi: open slide could not be saved (matrix built from last saved state) -> appended to result window
- veri-aktarma: chosen save location not found, default folder used -> line appended to the save-status line in the result window

## Deliberately left (log-only, with reason)

- nukleer-boya:168, er-pr-hscore:135, membran-boya:159, sitoplazmik-boya:131 (H-DAB default applied): follows an explicit user confirmation; informational.
- hucre-tespiti:126 (H&E applied after user confirmation): informational.
- veri-aktarma:627 (open slide not saved): already shown in result window (saveStatusLine, with Ctrl+S advice). veri-aktarma:912 / karo-disa-aktarma:1642 and omezarr:402: the log line only points to / duplicates the result window.
- yardimci-kosu-manifesti:306 (Atlandı): listed in the result window under 'Atlanan / hatalı'.
- yardimci-ozellik-matrisi:426 (large project, memory heavy): performance heads-up, no failure follows.
- yardimci-cytoformer-sihirbaz:723/737/740: headless-only (-a mevcutsinif) output, the user is at the console.
- '⚠️ Yalnızca araştırma/eğitim amaçlı...' lines: the required safety disclaimer, unchanged.
- yardimci-mitoz-retinanet:437 'Eski fastai ortamı' and mitoz-effnet/sanofi:227/234 'Bu sihirbaz QuPath arayüzü gerektirir': headless-only text.

## Recorded 'expressions' (script-core.json) not touched

None of the changed lines is a recorded expression (checked sihirbaz, quantum-ctcf, tumor-ici-ki67, tumor-stroma, veri-aktarma, yardimci-image-type, yardimci-ozellik-matrisi, membran-boya). The Alt betik hatası println itself is kept unchanged.

## Notes

- Alt betik hatası: I did NOT claim 'nothing was changed by the step' (cannot be verified; a helper may fail midway). Text says to run the step from its own menu entry and then press Refresh / reopen the wizard (tumor-stroma: restart the measurement).
- Not changed on purpose: 'Bölge okunamadı:' + exception in boya-vektor/boya-kalite-qc/tiatoolbox/doku-tespiti wizards, other 'okunamadı' strings (anonim, valis other, karo, metadata, maske-iceaktar): they already show a cause and are outside the 4 listed patterns; candidates for a second pass.