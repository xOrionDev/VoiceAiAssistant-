#!/usr/bin/env python3
# ============================================================================
#  Voice AI Assistant — AI processing worker (Phase A)
#
#  Runs on a GitHub Actions cron (~every 10 min). One pass does:
#    1. Poll Telegram  -> learn chat_id (/start) + handle button callbacks
#                         (Выполнено / Напомнить позже).
#    2. Read new entries from Supabase -> Gemini extracts tasks ->
#       write tasks with code-computed reminder dates.
#    3. Send due reminders to Telegram with inline buttons.
#
#  Secrets come from the environment (GitHub Actions secrets):
#    GEMINI_API_KEY, TELEGRAM_BOT_TOKEN, SUPABASE_SERVICE_KEY
#  Nothing secret is hard-coded.
# ============================================================================

import os
import re
import json
import datetime as dt
from zoneinfo import ZoneInfo

import requests

# --- config -----------------------------------------------------------------

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://batgckpricdljxzwdqmh.supabase.co").rstrip("/")
SERVICE_KEY  = os.environ["SUPABASE_SERVICE_KEY"]
TG_TOKEN     = os.environ["TELEGRAM_BOT_TOKEN"]
GEMINI_KEY   = os.environ["GEMINI_API_KEY"]

# Pluggable LLM: first model that answers wins. Override with GEMINI_MODEL.
GEMINI_MODELS = [m for m in [os.environ.get("GEMINI_MODEL")] if m] + [
    "gemini-2.0-flash",
    "gemini-2.5-flash",
    "gemini-flash-latest",
    "gemini-1.5-flash",
]

TZ = ZoneInfo("Asia/Dushanbe")           # UTC+5, no DST
REMIND_HOUR = 9                          # reminders fire at 09:00 local
MAX_ENTRIES_PER_RUN = 20

# Self-diagnostics: persisted to app_settings['worker_diag'] each run so the
# worker's state is readable from the Supabase dashboard (logs are unreachable).
DIAG = {"events": []}
def diag(msg):
    DIAG["events"].append(f"{dt.datetime.now(dt.timezone.utc).strftime('%H:%M:%S')} {msg}")
    print("[diag]", msg, flush=True)

SB = {
    "apikey": SERVICE_KEY,
    "Authorization": f"Bearer {SERVICE_KEY}",
    "Content-Type": "application/json",
}

def now_utc() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)

def iso(d: dt.datetime) -> str:
    return d.astimezone(dt.timezone.utc).isoformat()

def local_at(d: dt.datetime, hour: int = REMIND_HOUR) -> dt.datetime:
    """Return `d`'s calendar day at HH:00 local time, as UTC."""
    loc = d.astimezone(TZ).replace(hour=hour, minute=0, second=0, microsecond=0)
    return loc.astimezone(dt.timezone.utc)

def log(*a):
    print("[worker]", *a, flush=True)

# --- Supabase (PostgREST) ---------------------------------------------------

def sb_get(path):
    r = requests.get(f"{SUPABASE_URL}/rest/v1/{path}", headers=SB, timeout=30)
    r.raise_for_status()
    return r.json()

def sb_insert(table, row):
    r = requests.post(f"{SUPABASE_URL}/rest/v1/{table}", headers={**SB, "Prefer": "return=representation"},
                      data=json.dumps(row), timeout=30)
    r.raise_for_status()
    return r.json()

def sb_patch(path, row):
    r = requests.patch(f"{SUPABASE_URL}/rest/v1/{path}", headers={**SB, "Prefer": "return=representation"},
                       data=json.dumps(row), timeout=30)
    r.raise_for_status()
    return r.json()

def settings_get(key, default=None):
    rows = sb_get(f"app_settings?key=eq.{key}&select=value")
    return rows[0]["value"] if rows else default

def settings_set(key, value):
    r = requests.post(f"{SUPABASE_URL}/rest/v1/app_settings",
                      headers={**SB, "Prefer": "resolution=merge-duplicates,return=minimal"},
                      data=json.dumps({"key": key, "value": value}), timeout=30)
    r.raise_for_status()

# --- Telegram ---------------------------------------------------------------

TG = f"https://api.telegram.org/bot{TG_TOKEN}"

def tg(method, **params):
    r = requests.post(f"{TG}/{method}", json=params, timeout=40)
    try:
        return r.json()
    except Exception:
        return {"ok": False, "status": r.status_code, "text": r.text[:200]}

def tg_send(chat_id, text, task_id=None):
    kb = None
    if task_id:
        kb = {"inline_keyboard": [[
            {"text": "✅ Выполнено",      "callback_data": f"done:{task_id}"},
            {"text": "⏰ Напомнить позже", "callback_data": f"snooze:{task_id}"},
        ]]}
    res = tg("sendMessage", chat_id=chat_id, text=text, parse_mode="HTML",
             reply_markup=kb, disable_web_page_preview=True)
    if res.get("ok"):
        return res["result"]["message_id"]
    log("tg send failed:", res)
    return None

# --- 1) Poll Telegram: chat_id + callbacks ----------------------------------

def poll_telegram():
    state  = settings_get("telegram", {}) or {}
    offset = state.get("offset", 0)
    chat_id = state.get("chat_id")
    changed = False

    res = tg("getUpdates", offset=offset, timeout=0,
             allowed_updates=["message", "callback_query"])
    if not res.get("ok"):
        log("getUpdates failed:", res)
        return chat_id

    for upd in res.get("result", []):
        offset = max(offset, upd["update_id"] + 1)
        changed = True

        if "message" in upd:
            cid = upd["message"]["chat"]["id"]
            if chat_id != cid:
                chat_id = cid
                log("learned chat_id:", cid)
            txt = (upd["message"].get("text") or "").strip()
            if txt.startswith("/start"):
                tg_send(cid, "Привет! Я на связи. Буду присылать сюда напоминания по твоим задачам. "
                             "У каждого — кнопки «Выполнено» и «Напомнить позже».")

        elif "callback_query" in upd:
            cb = upd["callback_query"]
            data = cb.get("data", "")
            cid = cb["message"]["chat"]["id"]
            if chat_id != cid:
                chat_id = cid
            handle_callback(cb, data)

    if changed:
        settings_set("telegram", {"offset": offset, "chat_id": chat_id})
    return chat_id

def handle_callback(cb, data):
    m = re.match(r"(done|snooze):([0-9a-f\-]{36})", data)
    if not m:
        tg("answerCallbackQuery", callback_query_id=cb["id"])
        return
    action, task_id = m.group(1), m.group(2)
    rows = sb_get(f"tasks?id=eq.{task_id}&select=*")
    if not rows:
        tg("answerCallbackQuery", callback_query_id=cb["id"], text="Задача не найдена")
        return
    task = rows[0]
    chat_id = cb["message"]["chat"]["id"]
    msg_id  = cb["message"]["message_id"]

    if action == "done":
        sb_patch(f"tasks?id=eq.{task_id}",
                 {"status": "done", "next_reminder_at": None, "updated_at": iso(now_utc())})
        tg("editMessageText", chat_id=chat_id, message_id=msg_id,
           text=cb["message"]["text"] + "\n\n✅ <b>Выполнено</b>", parse_mode="HTML")
        tg("answerCallbackQuery", callback_query_id=cb["id"], text="Отмечено как выполнено 👌")
    else:  # snooze
        nxt, rule = snooze_time(task)
        if nxt is None:
            sb_patch(f"tasks?id=eq.{task_id}",
                     {"status": "expired", "next_reminder_at": None, "updated_at": iso(now_utc())})
            tg("answerCallbackQuery", callback_query_id=cb["id"], text="Больше напоминать не буду")
        else:
            sb_patch(f"tasks?id=eq.{task_id}",
                     {"next_reminder_at": iso(nxt), "reminder_rule": rule, "updated_at": iso(now_utc())})
            when = nxt.astimezone(TZ).strftime("%d.%m %H:%M")
            tg("editMessageText", chat_id=chat_id, message_id=msg_id,
               text=cb["message"]["text"] + f"\n\n⏰ Отложено до {when}", parse_mode="HTML")
            tg("answerCallbackQuery", callback_query_id=cb["id"], text=f"Напомню {when}")

def snooze_time(task):
    """Return (next_datetime_or_None, updated_rule)."""
    rule = task.get("reminder_rule") or {}
    kind = task["kind"]
    now = now_utc()
    if kind == "smalltask":
        left = int(rule.get("snoozes_left", 2))
        eod = local_at(now, 23).astimezone(dt.timezone.utc)  # ~end of local day
        if left <= 0 or now >= eod:
            return None, rule
        rule["snoozes_left"] = left - 1
        return now + dt.timedelta(hours=3), rule
    # deadline / recurring / idea -> remind again tomorrow morning
    return local_at(now + dt.timedelta(days=1)), rule

# --- 2) Entries -> Gemini -> tasks ------------------------------------------

EXTRACT_SCHEMA = {
    "type": "object",
    "properties": {
        "tasks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title":     {"type": "string"},
                    "details":   {"type": "string"},
                    "kind":      {"type": "string", "enum": ["deadline", "smalltask", "recurring", "idea"]},
                    "deadline":  {"type": "string"},   # YYYY-MM-DD or ""
                    "recipient": {"type": "string"},
                },
                "required": ["title", "kind"],
            },
        }
    },
    "required": ["tasks"],
}

def gemini_extract(entry):
    today = now_utc().astimezone(TZ)
    sys = (
        "Ты — ассистент, превращающий голосовую заметку (на русском) в задачи.\n"
        f"Сегодня {today.strftime('%Y-%m-%d (%A)')}, часовой пояс Asia/Dushanbe.\n\n"
        "ПРАВИЛА:\n"
        "- Извлекай только то, что явно сказано. НЕ придумывай задачи и дедлайны.\n"
        "- kind:\n"
        "  • deadline  — есть конкретный срок («до конца октября», «к пятнице»).\n"
        "  • smalltask — мелкое дело на ближайший день-два («отправить файлы завтра»).\n"
        "  • recurring — регулярное дело без срока.\n"
        "  • idea      — идея/замысел, который надо обдумать; без срока.\n"
        "- deadline: дата в формате YYYY-MM-DD, вычисли её от сегодняшней даты. "
        "Если срока нет — пустая строка.\n"
        "- recipient: кому адресовано (имя), если указано; иначе пусто.\n"
        "- Заметка типа 'thought' — почти всегда одна задача (idea/recurring/deadline).\n"
        "- Заметка типа 'recording' — извлекай задачи ТОЛЬКО если есть явное дело; "
        "если это просто разговор/заметка без действия — верни пустой список.\n"
        "- title — коротко и по делу; details — важные подробности из заметки.\n"
    )
    user = f"Тип заметки: {entry.get('type')}\nТекст: {entry.get('text','')}"

    payload = {
        "systemInstruction": {"parts": [{"text": sys}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": {
            "temperature": 0.2,
            "responseMimeType": "application/json",
            "responseSchema": EXTRACT_SCHEMA,
        },
    }

    last_err = None
    for model in GEMINI_MODELS:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_KEY}"
        try:
            r = requests.post(url, json=payload, timeout=60)
            if r.status_code != 200:
                last_err = f"{model}: HTTP {r.status_code} {r.text[:160]}"
                if r.status_code == 404:
                    continue          # model name not available — try next
                break                 # auth/quota/other — same for all models, stop
            data = r.json()
            text = data["candidates"][0]["content"]["parts"][0]["text"]
            parsed = json.loads(text)
            diag(f"gemini ok ({model}): {len(parsed.get('tasks', []))} task(s)")
            return parsed.get("tasks", [])
        except Exception as e:
            last_err = f"{model}: {type(e).__name__} {e}"
            continue
    diag(f"gemini FAIL {last_err}")
    return None  # None = couldn't process (don't mark entry done)

def parse_deadline(s):
    if not s:
        return None
    try:
        d = dt.date.fromisoformat(s.strip()[:10])
    except Exception:
        return None
    today = now_utc().astimezone(TZ).date()
    # sanity: not in the past, not absurdly far out
    if d < today or d > today + dt.timedelta(days=730):
        return None
    return dt.datetime(d.year, d.month, d.day, tzinfo=TZ).astimezone(dt.timezone.utc)

def build_schedule(kind, deadline):
    """Return (next_reminder_at, reminder_rule) given kind + deadline datetime (UTC|None)."""
    now = now_utc()
    if kind == "deadline" and deadline:
        # spread reminders before the deadline, denser near the end
        offsets = [25, 15, 7, 3, 1, 0]  # days before deadline
        times = []
        for off in offsets:
            t = local_at(deadline - dt.timedelta(days=off))
            if t > now:
                times.append(t)
        times = sorted(set(times))
        if not times:                       # deadline very close -> remind now-ish
            times = [local_at(now)]
        return times[0], {"times": [iso(t) for t in times]}
    if kind == "smalltask":
        # remind on the target day (or tomorrow if none), snooze-able within the day
        target = deadline if deadline else now + dt.timedelta(days=1)
        return local_at(target), {"snoozes_left": 2}
    # recurring / idea -> weekly, starting in a week
    return local_at(now + dt.timedelta(days=7)), {}

def process_entries():
    entries = sb_get(f"entries?processed_at=is.null&select=*&order=created_at.asc&limit={MAX_ENTRIES_PER_RUN}")
    diag(f"entries unprocessed: {len(entries)}")
    created = 0
    for e in entries:
        tasks = gemini_extract(e)
        if tasks is None:
            continue  # LLM error — leave unprocessed, retry next run
        diag(f"entry {str(e.get('id'))[:8]} ({e.get('type')}) -> {len(tasks)} task(s)")
        for t in tasks:
            created += 1
            kind = t.get("kind", "smalltask")
            deadline = parse_deadline(t.get("deadline", ""))
            if kind == "deadline" and not deadline:
                kind = "idea"  # claimed a deadline but gave none usable -> backlog
            nxt, rule = build_schedule(kind, deadline)
            sb_insert("tasks", {
                "user_id": e.get("user_id"),
                "source_entry_id": e.get("id"),
                "title": (t.get("title") or "").strip()[:200] or "Задача",
                "details": (t.get("details") or "").strip(),
                "kind": kind,
                "recipient": (t.get("recipient") or "").strip() or None,
                "lang": e.get("lang", "ru"),
                "deadline": iso(deadline) if deadline else None,
                "next_reminder_at": iso(nxt) if nxt else None,
                "reminder_rule": rule,
            })
            log(f"  + task [{kind}] {t.get('title')}")
        sb_patch(f"entries?id=eq.{e['id']}", {"processed_at": iso(now_utc())})
    diag(f"tasks created this run: {created}")

# --- 3) Send due reminders --------------------------------------------------

KIND_LABEL = {"deadline": "📌 Задача со сроком", "smalltask": "✉️ Небольшое дело",
              "recurring": "🔁 Регулярное", "idea": "💡 Идея"}

def send_due_reminders(chat_id):
    if not chat_id:
        diag("no chat_id yet — skip reminders (press /start)")
        return
    due = sb_get(f"tasks?status=eq.open&next_reminder_at=lte.{iso(now_utc())}"
                 f"&next_reminder_at=not.is.null&select=*&order=next_reminder_at.asc")
    diag(f"reminders due: {len(due)} (chat_id set)")
    for task in due:
        head = KIND_LABEL.get(task["kind"], "🔔 Напоминание")
        lines = [f"{head}", f"<b>{task['title']}</b>"]
        if task.get("details"):
            lines.append(task["details"])
        if task.get("recipient"):
            lines.append(f"→ {task['recipient']}")
        if task.get("deadline"):
            d = dt.datetime.fromisoformat(task["deadline"]).astimezone(TZ)
            lines.append(f"Срок: {d.strftime('%d.%m.%Y')}")
        mid = tg_send(chat_id, "\n".join(lines), task_id=task["id"])

        # advance to the next scheduled time
        nxt = advance_after_send(task)
        patch = {"tg_message_id": mid, "updated_at": iso(now_utc())}
        patch["next_reminder_at"] = iso(nxt) if nxt else None
        sb_patch(f"tasks?id=eq.{task['id']}", patch)

def advance_after_send(task):
    now = now_utc()
    kind = task["kind"]
    rule = task.get("reminder_rule") or {}
    if kind == "deadline":
        times = [dt.datetime.fromisoformat(t) for t in rule.get("times", [])]
        future = [t for t in times if t > now]
        return min(future) if future else None
    if kind in ("recurring", "idea"):
        return local_at(now + dt.timedelta(days=7))
    return None  # smalltask: one-shot (further nudges only via snooze button)

# --- main -------------------------------------------------------------------

def main():
    log("run start", iso(now_utc()))
    diag("run start")
    chat_id = None
    try:
        chat_id = poll_telegram()
    except Exception as e:
        diag(f"poll_telegram ERROR {type(e).__name__} {e}")
    diag(f"chat_id={'set' if chat_id else 'none'}")
    try:
        process_entries()
    except Exception as e:
        diag(f"process_entries ERROR {type(e).__name__} {e}")
    try:
        if chat_id is None:
            chat_id = (settings_get("telegram", {}) or {}).get("chat_id")
        send_due_reminders(chat_id)
    except Exception as e:
        diag(f"send_due_reminders ERROR {type(e).__name__} {e}")
    # Persist this run's diagnostics so it can be read from the DB dashboard.
    try:
        settings_set("worker_diag", {"at": iso(now_utc()), "events": DIAG["events"][-40:]})
    except Exception as e:
        log("diag persist failed:", e)
    log("run done")

if __name__ == "__main__":
    main()
