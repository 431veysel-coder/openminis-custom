import io, pathlib

root = pathlib.Path("/var/minis/shared/mxrepo")
p = root / "src/android/app/src/main/java/com/openminis/app/ozel/PolicyGate.kt"
s = io.open(p, encoding="utf-8").read()

old = '''        val rule = RiskRules.byId(verdict.ruleId)
        if (rule != null && mode.autoGrants(rule)) {
            val grant = ApprovalPolicy.grantFor(
                request = request,
                scope = ApprovalScope.Global,
                decision = Decision.ALLOW,
                nowMs = now,
                note = "Ayarlar › İzinler · mod: ${mode.key}",
            )
            book.put(grant)
            book.consume(grant, now)
            persist(ctx, book)
            return GateDecision.Silent(verdict, grant)
        }'''

new = '''        val rule = RiskRules.byId(verdict.ruleId)
        if (rule != null && mode.autoGrants(rule)) {
            val grant = ApprovalPolicy.grantFor(
                request = request,
                scope = ApprovalScope.Global,
                decision = Decision.ALLOW,
                nowMs = now,
                note = "Ayarlar › İzinler · mod: ${mode.key}",
            )
            book.put(grant)
            book.consume(grant, now)
            persist(ctx, book)

            // [37118] Kart kalktı, **haber** kalkmadı.
            //
            // Geri alınamaz ya da sandbox dışı bir iş artık beklenmiyor ama
            // söyleniyor: telefon tek cümle söyler, titrer, bildirim bırakır
            // ve iş devam eder. Kullanıcının şikâyeti "onay veremiyorum"du;
            // "ne olduğunu bilmiyorum" değil. Bu satır olmadan `ALLOW_ALL`,
            // sessiz bir silme makinesi olurdu.
            if (mode.notifyOnly(rule)) {
                runCatching { ApprovalVoice.notice(ctx, request) }
            }
            return GateDecision.Silent(verdict, grant)
        }'''
assert old in s, "aradigim blok bulunamadi"
s = s.replace(old, new)
io.open(p, "w", encoding="utf-8").write(s)
print("PolicyGate: notifyOnly baglandi")
