# Beş ilk kullanım tanımı — onaylandı ve kaynakları kontrol edildi

Yazar, 14 Kasım 2026'da aşağıdaki taslağı “Taslağı kaynak kontrolünden sonra uygulayın” yanıtıyla onayladı. *epoch* ve VUS kararları daha önce uygulandı. Bu beş grup da ortak sözlükte iki dil/iki kitle tanımlarıyla onaylı satırlardır. Türkçe karşılıklar yazar kararıdır; kaynaklar kavramları doğrular.

| EN | Önerilen TR ilk kullanım | Patologlar için tanım | Bilgisayar bilimciler için tanım |
|---|---|---|---|
| Terminal / command prompt / console | **Terminal (komut penceresi)**; Windows aracı **Komut İstemi (cmd.exe)**; uygulamadaki çıktı alanı **konsol** | Komutu yazıp Enter'a bastığınız pencere ile programın sonucunu gösteren alan farklı olabilir. | Terminal/shell UI and application output console are distinct; name the actual shell used by each command. |
| Folder / directory / working directory | **Klasör (dizin)**; devamında **klasör**; **çalışma klasörü** | Dosyalarınızı sakladığınız yerdir; çalışma klasörü, bir komutun o anda dosya okuyup yazdığı konumdur. | Directory and current working directory; relative paths are resolved against the latter. |
| Virtual environment | **Sanal ortam** | Bu işlem için gereken Python paketlerini diğer işlerinizden ayrı tutan klasördür. | Project-specific interpreter/package environment; it is not an operating-system virtual machine. |
| Tensor | **Tensör** | Bir görüntü veya görüntü grubunun sayılardan oluşan, eksenleri tanımlı çok boyutlu dizisidir. | Multidimensional numerical array; document axis meanings, shape, dtype and device. |
| Sample / patient | **Örnek** ve **hasta**, birbirinin yerine kullanılmaz | Bir hastadan birden çok doku örneği, blok veya lam gelebilir. | Distinguish observation and grouping units; preserve the sample-to-patient mapping in dataset splits. |

Kaynak kontrolü (14 Kasım 2026):

- [Microsoft Windows Terminal](https://learn.microsoft.com/en-us/windows/terminal/) terminalin Komut İstemi ve PowerShell gibi komut yorumlayıcılarını barındırdığını; [cmd belgesi](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/cmd) `cmd.exe` aracını doğrular. Uygulamanın çıktı konsolu ayrı bağlamda adlandırılır; Python komutları QuPath betik editörüne yapıştırılmaz.
- [Python pathlib](https://docs.python.org/3/library/pathlib.html) dosya/klasör yollarını; [os.getcwd](https://docs.python.org/3/library/os.html#os.getcwd) o andaki çalışma klasörünü tanımlar. Göreli yolların başlangıcı, betiğin bulunduğu klasör olmak zorunda değildir.
- [Python venv](https://docs.python.org/3/library/venv.html) sanal ortamın projeye ait Python yorumlayıcısı ve ayrı paket kümesini içerdiğini doğrular. Başlangıç açıklaması bu ayrımı belirtir.
- [PyTorch tensör öğreticisi](https://docs.pytorch.org/tutorials/beginner/basics/tensorqs_tutorial.html) boyut/şekil, veri türü ve aygıt bilgisini doğrular. Görüntü eksenleri ayrıca adlandırılır.
- [GDC TCGA barkodu](https://docs.gdc.cancer.gov/Encyclopedia/pages/TCGA_Barcode/) katılımcı→örnek→lam hiyerarşisini gösterir. [scikit-learn grup bölmesi](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.GroupShuffleSplit.html), bir grup kimliğine ait gözlemlerin aynı bölmede tutulmasını destekler; hasta kimliği bu derslerde grup kimliğidir.

Uygulama: dört deponun byte-identical GLOSSARY kopyası ve SHA kaydı; üç sitenin TR/EN sözlük sayfaları; QuPath kaynaklar/betik/veri dışa aktarma başlangıçları ve tensör açıklaması; cBioPortal'ın klasör/terminal/sanal ortam başlangıcı ve örnek–hasta ayrımı. Komutlar, dosya adları, CSV/API alanları ve analiz davranışı bu terim düzenlemesinde değiştirilmez.
