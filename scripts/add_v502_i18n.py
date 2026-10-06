# -*- coding: utf-8 -*-
"""v5.0.2: 把 ConnectionDiagnoseDialog 里「一键复制诊断信息」的硬编码中文
抽取到 10 个语种的 strings.xml（保持各语种 key 完全一致）。

用法: python scripts/add_diag_i18n.py
幂等：已存在的 key 会先删除再写入。
"""
import io
import os
import re

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "android_app", "app", "src", "main", "res")

# key -> {locale: text}；locale "values" 为默认（中文简体）
T = {
    "diag_040": {
        "values": "诊断信息", "values-en": "Diagnostics", "values-ja": "診断情報",
        "values-ko": "진단 정보", "values-es": "Diagnóstico", "values-fr": "Diagnostics",
        "values-de": "Diagnoseinformationen", "values-ru": "Диагностика",
        "values-pt": "Diagnóstico", "values-ar": "معلومات التشخيص",
    },
    "diag_041": {
        "values": "App 版本：%1$s", "values-en": "App version: %1$s",
        "values-ja": "アプリ版：%1$s", "values-ko": "앱 버전: %1$s",
        "values-es": "Versión de la app: %1$s", "values-fr": "Version de l'app : %1$s",
        "values-de": "App-Version: %1$s", "values-ru": "Версия приложения: %1$s",
        "values-pt": "Versão do app: %1$s", "values-ar": "إصدار التطبيق: %1$s",
    },
    "diag_042": {
        "values": "连接模式：%1$s", "values-en": "Connection mode: %1$s",
        "values-ja": "接続モード：%1$s", "values-ko": "연결 모드: %1$s",
        "values-es": "Modo de conexión: %1$s", "values-fr": "Mode de connexion : %1$s",
        "values-de": "Verbindungsmodus: %1$s", "values-ru": "Режим подключения: %1$s",
        "values-pt": "Modo de conexão: %1$s", "values-ar": "وضع الاتصال: %1$s",
    },
    "diag_043": {
        "values": "服务器地址：%1$s", "values-en": "Server address: %1$s",
        "values-ja": "サーバーアドレス：%1$s", "values-ko": "서버 주소: %1$s",
        "values-es": "Dirección del servidor: %1$s",
        "values-fr": "Adresse du serveur : %1$s", "values-de": "Serveradresse: %1$s",
        "values-ru": "Адрес сервера: %1$s", "values-pt": "Endereço do servidor: %1$s",
        "values-ar": "عنوان الخادم: %1$s",
    },
    "diag_044": {
        "values": "连接状态：%1$s", "values-en": "Connection state: %1$s",
        "values-ja": "接続状態：%1$s", "values-ko": "연결 상태: %1$s",
        "values-es": "Estado de conexión: %1$s",
        "values-fr": "État de la connexion : %1$s", "values-de": "Verbindungsstatus: %1$s",
        "values-ru": "Состояние подключения: %1$s", "values-pt": "Estado da conexão: %1$s",
        "values-ar": "حالة الاتصال: %1$s",
    },
    "diag_045": {
        "values": "最近错误：%1$s", "values-en": "Last error: %1$s",
        "values-ja": "直近のエラー：%1$s", "values-ko": "최근 오류: %1$s",
        "values-es": "Último error: %1$s", "values-fr": "Dernière erreur : %1$s",
        "values-de": "Letzter Fehler: %1$s", "values-ru": "Последняя ошибка: %1$s",
        "values-pt": "Último erro: %1$s", "values-ar": "آخر خطأ: %1$s",
    },
    "diag_046": {
        "values": "Android 版本：%1$s", "values-en": "Android version: %1$s",
        "values-ja": "Android バージョン：%1$s", "values-ko": "Android 버전: %1$s",
        "values-es": "Versión de Android: %1$s", "values-fr": "Version d'Android : %1$s",
        "values-de": "Android-Version: %1$s", "values-ru": "Версия Android: %1$s",
        "values-pt": "Versão do Android: %1$s", "values-ar": "إصدار Android: %1$s",
    },
    "diag_047": {
        "values": "设备型号：%1$s", "values-en": "Device model: %1$s",
        "values-ja": "端末モデル：%1$s", "values-ko": "기기 모델: %1$s",
        "values-es": "Modelo del dispositivo: %1$s",
        "values-fr": "Modèle de l'appareil : %1$s", "values-de": "Gerätemodell: %1$s",
        "values-ru": "Модель устройства: %1$s",
        "values-pt": "Modelo do dispositivo: %1$s", "values-ar": "طراز الجهاز: %1$s",
    },
    "diag_048": {
        "values": "电脑中继", "values-en": "Desktop relay", "values-ja": "PC リレー",
        "values-ko": "PC 릴레이", "values-es": "Retransmisión de PC",
        "values-fr": "Relais PC", "values-de": "PC-Relay", "values-ru": "Релей ПК",
        "values-pt": "Retransmissão do PC", "values-ar": "مرحّل الكمبيوتر",
    },
    "diag_049": {
        "values": "云端直连", "values-en": "Cloud direct", "values-ja": "クラウド直結",
        "values-ko": "클라우드 직접 연결", "values-es": "Nube directa",
        "values-fr": "Cloud direct", "values-de": "Cloud-Direkt",
        "values-ru": "Прямое облако", "values-pt": "Nuvem direta",
        "values-ar": "سحابة مباشرة",
    },
    "diag_050": {
        "values": "未配置", "values-en": "Not configured", "values-ja": "未設定",
        "values-ko": "미설정", "values-es": "Sin configurar", "values-fr": "Non configuré",
        "values-de": "Nicht konfiguriert", "values-ru": "Не настроено",
        "values-pt": "Não configurado", "values-ar": "غير مُهيّأ",
    },
    "diag_051": {
        "values": "无", "values-en": "None", "values-ja": "なし", "values-ko": "없음",
        "values-es": "Ninguno", "values-fr": "Aucun", "values-de": "Keine",
        "values-ru": "Нет", "values-pt": "Nenhum", "values-ar": "لا يوجد",
    },
    "diag_052": {
        "values": "延迟：%1$s", "values-en": "Latency: %1$s", "values-ja": "遅延：%1$s",
        "values-ko": "지연: %1$s", "values-es": "Latencia: %1$s",
        "values-fr": "Latence : %1$s", "values-de": "Latenz: %1$s",
        "values-ru": "Задержка: %1$s", "values-pt": "Latência: %1$s",
        "values-ar": "زمن الاستجابة: %1$s",
    },
    # RelayWebSocketClient 的 E2EE 失败提示（原先硬编码中文，用户可见）
    "relay_n01": {
        "values": "E2EE 加密失败，已拒绝发送：%1$s",
        "values-en": "E2EE encryption failed, sending refused: %1$s",
        "values-ja": "E2EE 暗号化に失敗したため送信を中止しました：%1$s",
        "values-ko": "E2EE 암호화에 실패하여 전송을 중단했습니다: %1$s",
        "values-es": "Falló el cifrado E2EE, envío cancelado: %1$s",
        "values-fr": "Échec du chiffrement E2EE, envoi refusé : %1$s",
        "values-de": "E2EE-Verschlüsselung fehlgeschlagen, Senden abgelehnt: %1$s",
        "values-ru": "Сбой шифрования E2EE, отправка отменена: %1$s",
        "values-pt": "Falha na criptografia E2EE, envio cancelado: %1$s",
        "values-ar": "فشل تشفير E2EE، تم رفض الإرسال: %1$s",
    },
    # E2eeManager 的失败原因：安全类不持有 Context，改为错误码 + 在这里给文案
    "e2ee_n01": {
        "values": "本机 E2EE 私钥缺失",
        "values-en": "This device's E2EE private key is missing",
        "values-ja": "本機の E2EE 秘密鍵が見つかりません",
        "values-ko": "이 기기의 E2EE 개인 키가 없습니다",
        "values-es": "Falta la clave privada E2EE de este dispositivo",
        "values-fr": "La clé privée E2EE de cet appareil est absente",
        "values-de": "Der private E2EE-Schlüssel dieses Geräts fehlt",
        "values-ru": "Отсутствует закрытый ключ E2EE этого устройства",
        "values-pt": "A chave privada E2EE deste dispositivo está ausente",
        "values-ar": "مفتاح E2EE الخاص بهذا الجهاز مفقود",
    },
    "e2ee_n02": {
        "values": "尚未与 %1$s 协商 E2EE 公钥",
        "values-en": "E2EE public key not yet negotiated with %1$s",
        "values-ja": "%1$s と E2EE 公開鍵をまだ交換していません",
        "values-ko": "%1$s와(과) E2EE 공개 키를 아직 교환하지 않았습니다",
        "values-es": "Aún no se ha negociado la clave pública E2EE con %1$s",
        "values-fr": "Clé publique E2EE pas encore négociée avec %1$s",
        "values-de": "E2EE-Schlüssel wurde mit %1$s noch nicht ausgetauscht",
        "values-ru": "Открытый ключ E2EE ещё не согласован с %1$s",
        "values-pt": "Chave pública E2EE ainda não negociada com %1$s",
        "values-ar": "لم يتم بعد تبادل مفتاح E2EE العام مع %1$s",
    },
    "e2ee_n03": {
        "values": "本机 relay device_id 缺失，请重新配对",
        "values-en": "This device's relay device_id is missing; please pair again",
        "values-ja": "本機の relay device_id がありません。再ペアリングしてください",
        "values-ko": "이 기기의 relay device_id가 없습니다. 다시 페어링하세요",
        "values-es": "Falta el relay device_id de este dispositivo; vuelve a emparejar",
        "values-fr": "relay device_id de cet appareil manquant ; réappairez",
        "values-de": "relay device_id dieses Geräts fehlt; bitte neu koppeln",
        "values-ru": "Отсутствует relay device_id этого устройства; выполните сопряжение заново",
        "values-pt": "relay device_id deste dispositivo ausente; emparede novamente",
        "values-ar": "معرّف relay device_id لهذا الجهاز مفقود؛ أعد الاقتران",
    },
    "e2ee_n04": {
        "values": "未知加密错误",
        "values-en": "Unknown encryption error",
        "values-ja": "不明な暗号化エラー",
        "values-ko": "알 수 없는 암호화 오류",
        "values-es": "Error de cifrado desconocido",
        "values-fr": "Erreur de chiffrement inconnue",
        "values-de": "Unbekannter Verschlüsselungsfehler",
        "values-ru": "Неизвестная ошибка шифрования",
        "values-pt": "Erro de criptografia desconhecido",
        "values-ar": "خطأ تشفير غير معروف",
    },
    # 界面里残留的硬编码英文（Text 标签）与 contentDescription（无障碍朗读）
    "model_n03": {
        "values": "Model / Agent", "values-en": "Model / Agent",
        "values-ja": "モデル / エージェント", "values-ko": "모델 / 에이전트",
        "values-es": "Modelo / Agente", "values-fr": "Modèle / Agent",
        "values-de": "Modell / Agent", "values-ru": "Модель / агент",
        "values-pt": "Modelo / Agente", "values-ar": "النموذج / الوكيل",
    },
    "model_n04": {
        "values": "Agent (%1$d)", "values-en": "Agent (%1$d)",
        "values-ja": "エージェント (%1$d)", "values-ko": "에이전트 (%1$d)",
        "values-es": "Agente (%1$d)", "values-fr": "Agent (%1$d)",
        "values-de": "Agent (%1$d)", "values-ru": "Агент (%1$d)",
        "values-pt": "Agente (%1$d)", "values-ar": "الوكيل (%1$d)",
    },
    "model_n05": {
        "values": "Model (%1$d)", "values-en": "Model (%1$d)",
        "values-ja": "モデル (%1$d)", "values-ko": "모델 (%1$d)",
        "values-es": "Modelo (%1$d)", "values-fr": "Modèle (%1$d)",
        "values-de": "Modell (%1$d)", "values-ru": "Модель (%1$d)",
        "values-pt": "Modelo (%1$d)", "values-ar": "النموذج (%1$d)",
    },
    "chat_n01": {
        "values": "置顶", "values-en": "Pin", "values-ja": "ピン留め",
        "values-ko": "고정", "values-es": "Fijar", "values-fr": "Épingler",
        "values-de": "Anheften", "values-ru": "Закрепить", "values-pt": "Fixar",
        "values-ar": "تثبيت",
    },
    "chat_n02": {
        "values": "归档", "values-en": "Archive", "values-ja": "アーカイブ",
        "values-ko": "보관", "values-es": "Archivar", "values-fr": "Archiver",
        "values-de": "Archivieren", "values-ru": "В архив", "values-pt": "Arquivar",
        "values-ar": "أرشفة",
    },
    "chat_n03": {
        "values": "切换会话", "values-en": "Switch session", "values-ja": "セッション切替",
        "values-ko": "세션 전환", "values-es": "Cambiar de sesión",
        "values-fr": "Changer de session", "values-de": "Sitzung wechseln",
        "values-ru": "Сменить сессию", "values-pt": "Trocar sessão",
        "values-ar": "تبديل الجلسة",
    },
    "chat_n04": {
        "values": "搜索日志", "values-en": "Search log", "values-ja": "ログ検索",
        "values-ko": "로그 검색", "values-es": "Buscar en el registro",
        "values-fr": "Rechercher dans le journal", "values-de": "Log durchsuchen",
        "values-ru": "Поиск в журнале", "values-pt": "Pesquisar no log",
        "values-ar": "بحث في السجل",
    },
    "chat_n05": {
        "values": "更多选项", "values-en": "More options", "values-ja": "その他のオプション",
        "values-ko": "더보기", "values-es": "Más opciones",
        "values-fr": "Plus d'options", "values-de": "Weitere Optionen",
        "values-ru": "Дополнительно", "values-pt": "Mais opções",
        "values-ar": "مزيد من الخيارات",
    },
    "chat_n06": {
        "values": "清空", "values-en": "Clear", "values-ja": "クリア",
        "values-ko": "지우기", "values-es": "Limpiar", "values-fr": "Effacer",
        "values-de": "Leeren", "values-ru": "Очистить", "values-pt": "Limpar",
        "values-ar": "مسح",
    },
    "chat_n07": {
        "values": "关闭", "values-en": "Dismiss", "values-ja": "閉じる",
        "values-ko": "닫기", "values-es": "Cerrar", "values-fr": "Fermer",
        "values-de": "Schließen", "values-ru": "Закрыть", "values-pt": "Fechar",
        "values-ar": "إغلاق",
    },
    "chat_n08": {
        "values": "停止", "values-en": "Stop", "values-ja": "停止",
        "values-ko": "중지", "values-es": "Detener", "values-fr": "Arrêter",
        "values-de": "Stopp", "values-ru": "Стоп", "values-pt": "Parar",
        "values-ar": "إيقاف",
    },
    "chat_n09": {
        "values": "发送", "values-en": "Send", "values-ja": "送信",
        "values-ko": "전송", "values-es": "Enviar", "values-fr": "Envoyer",
        "values-de": "Senden", "values-ru": "Отправить", "values-pt": "Enviar",
        "values-ar": "إرسال",
    },
    "pair_n01": {
        "values": "OpenCode 图标", "values-en": "OpenCode logo", "values-ja": "OpenCode ロゴ",
        "values-ko": "OpenCode 로고", "values-es": "Logotipo de OpenCode",
        "values-fr": "Logo OpenCode", "values-de": "OpenCode-Logo",
        "values-ru": "Логотип OpenCode", "values-pt": "Logotipo do OpenCode",
        "values-ar": "شعار OpenCode",
    },
    "pair_n02": {
        "values": "显示/隐藏密钥", "values-en": "Show or hide secret",
        "values-ja": "シークレットの表示切替", "values-ko": "시크릿 표시 전환",
        "values-es": "Mostrar u ocultar secreto",
        "values-fr": "Afficher/masquer le secret",
        "values-de": "Secret ein-/ausblenden",
        "values-ru": "Показать/скрыть секрет", "values-pt": "Mostrar/ocultar segredo",
        "values-ar": "إظهار/إخفاء السر",
    },
    "pair_n03": {
        "values": "显示/隐藏云端 Key", "values-en": "Show or hide cloud key",
        "values-ja": "クラウドキーの表示切替", "values-ko": "클라우드 키 표시 전환",
        "values-es": "Mostrar u ocultar clave de nube",
        "values-fr": "Afficher/masquer la clé cloud",
        "values-de": "Cloud-Schlüssel ein-/ausblenden",
        "values-ru": "Показать/скрыть облачный ключ",
        "values-pt": "Mostrar/ocultar chave da nuvem",
        "values-ar": "إظهار/إخفاء مفتاح السحابة",
    },
    # PairingCoordinator：配对时对端公钥 HMAC 校验失败（用户可见错误条）
    "vm_n01": {
        "values": "对端公钥认证失败，已拒绝（疑似中继篡改）",
        "values-en": "Peer public key verification failed; rejected (possible relay tampering)",
        "values-ja": "相手の公開鍵の検証に失敗したため拒否しました（中継による改ざんの疑い）",
        "values-ko": "상대 공개 키 검증에 실패하여 거부했습니다 (릴레이 변조 의심)",
        "values-es": "Falló la verificación de la clave pública del par; rechazado "
                     "(posible manipulación del relay)",
        "values-fr": "Échec de la vérification de la clé publique du pair ; refusé "
                     "(altération possible du relais)",
        "values-de": "Verifizierung des öffentlichen Schlüssels fehlgeschlagen; abgelehnt "
                     "(mögliche Relay-Manipulation)",
        "values-ru": "Проверка открытого ключа не удалась; отклонено "
                     "(возможна подмена ретранслятором)",
        "values-pt": "Falha na verificação da chave pública do par; recusado "
                     "(possível adulteração do relay)",
        "values-ar": "فشل التحقق من المفتاح العام للطرف الآخر؛ تم الرفض "
                     "(احتمال تلاعب المرحّل)",
    },
}


def esc(s: str) -> str:
    """XML 转义：& < > 以及撇号（Android 字符串资源里 ' 需转义或整体加引号）。"""
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'")
    return s


# 项目对「里程碑之后新增」的资源沿用 _nNN 命名（已存在 diag_n01）。
RENAME = {
    "diag_040": "diag_n02",   # 诊断信息
    "diag_041": "diag_n03",   # App 版本
    "diag_042": "diag_n04",   # 连接模式
    "diag_043": "diag_n05",   # 服务器地址
    "diag_044": "diag_n06",   # 连接状态
    "diag_045": "diag_n08",   # 最近错误
    "diag_046": "diag_n09",   # Android 版本
    "diag_047": "diag_n10",   # 设备型号
    "diag_048": "diag_n11",   # 电脑中继
    "diag_049": "diag_n12",   # 云端直连
    "diag_051": "diag_n13",   # 无
    "diag_052": "diag_n07",   # 延迟
}
# 「未配置」已有 diag_n01，不重复添加（避免文案重复定义）
SKIP = {"diag_050"}

EFFECTIVE = {RENAME.get(k, k): v for k, v in T.items() if k not in SKIP}
# 幂等清理：既删上一版可能写入的旧名，也删本次写入的新名
CLEANUP = list(T.keys()) + list(EFFECTIVE.keys())


def main():
    locales = sorted({loc for entry in EFFECTIVE.values() for loc in entry})
    total = 0
    for loc in locales:
        path = os.path.join(RES, loc, "strings.xml")
        if not os.path.exists(path):
            raise SystemExit(f"missing locale dir: {loc}")
        src = io.open(path, encoding="utf-8").read()
        # 幂等：先删掉同名 key（含上一版的临时命名）
        for key in CLEANUP:
            src = re.sub(r'\n\s*<string name="%s">.*?</string>' % key, "", src, flags=re.S)
        lines = []
        for key, entry in sorted(EFFECTIVE.items()):
            if loc not in entry:
                raise SystemExit(f"locale {loc} missing translation for {key}")
            lines.append('    <string name="%s">%s</string>' % (key, esc(entry[loc])))
        block = "\n".join(lines)
        src = src.replace("</resources>", block + "\n</resources>")
        io.open(path, "w", encoding="utf-8").write(src)
        n = len(re.findall(r"<string name=", src))
        print(f"{loc:14} -> {n} strings")
        total += 1

    # 一致性自检：所有语种 key 集合完全一致；且不得再引用被跳过的重复 key
    sets = {}
    for loc in locales:
        src = io.open(os.path.join(RES, loc, "strings.xml"), encoding="utf-8").read()
        sets[loc] = set(re.findall(r'<string name="([^"]+)"', src))
    base = sets["values"]
    for loc in locales:
        assert sets[loc] == base, f"key mismatch in {loc}: " \
                                  f"missing={base - sets[loc]} extra={sets[loc] - base}"
    for k in SKIP:
        assert k not in base, f"{k} 已存在等价资源，不应重复定义"
    print(f"\nlocales={total}  keys={len(base)}  parity=OK  (+{len(EFFECTIVE)} new keys)")


if __name__ == "__main__":
    main()
