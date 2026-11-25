import io, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")
p = root / "src/android/app/src/main/java/com/openminis/app/ui/team/CouncilScreens.kt"
s = io.open(p, encoding="utf-8").read()

# 1) DecisionTab tanimini geri koy (dosyanin sonuna, yardimci fonksiyondan once)
anchor = "/** Duruşu tek karaktere indirir; renk yanında taşındığı için okunur kalır. */"
assert anchor in s, "capraz dogrulama: aranan yorum bulunamadi"

missing = '''/**
 * [37099k · §8.2] **Karar odası** — rapor düz metin değil.
 *
 * `ReportBody` başlık/madde/atıf ayırıyor; önce tek `Text`'ti ve modelin
 * yazdığı `## başlık` ile `- madde` ekranda ham görünüyordu.
 *
 * [37124] GERÇEK HATA: bu fonksiyon `.41`'de (matris yaması) yanlışlıkla
 * silindi; çağrısı kaldı (sekme 2) ve hiçbir kapı yakalamadı, çünkü
 * `Slice Gate`'in yol listesinde `ui/team/**` **yoktu** — yani bu dosya CI'da
 * hiç derlenmiyordu. Aynı turda yol listesine eklendi.
 */
@Composable
private fun DecisionTab(
    answer: String?,
    dissent: List<String>,
    recorded: TeamRunStore.Header?,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (recorded != null && !recorded.verified) {
                    Text(
                        stringResource(R.string.council_unverified),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (answer.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.council_no_report),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ReportBody(answer)
                }
                if (dissent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.council_dissent),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    dissent.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

'''
s = s.replace(anchor, missing + anchor, 1)

# TeamRunStore importu var mi
if "import com.openminis.app.agent.team.TeamRunStore" not in s:
    s = s.replace("import com.openminis.app.agent.team.TeamTaskState",
                  "import com.openminis.app.agent.team.TeamRunStore\nimport com.openminis.app.agent.team.TeamTaskState", 1)
io.open(p, "w", encoding="utf-8").write(s)
print("DecisionTab geri konuldu")
