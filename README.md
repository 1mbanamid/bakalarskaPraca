# AI Project Planner

AI Project Planner je full-stack webová aplikácia (bakalárska práca) ktorá pomocou Azure OpenAI generuje projektové plány podľa metodológií **PRINCE2** a **Scrum**, umožňuje ich interaktívne editovanie a exportuje úlohy do **Jira Cloud**.

---

## Funkcie

| Oblasť | Popis |
|---|---|
| 🤖 **AI generovanie** | Dual-prompt: PRINCE2 (fázy → úlohy → výstupy → riziká) + Scrum (sprinty → epics → stories → sub-tasky), SK/EN |
| 📊 **Dashboard** | Interaktívny porovnávací dashboard PRINCE2 vs Scrum — radar chart, donut grafy, časová os, sprint velocity |
| ✏️ **Inline editovanie** | Editácia každého poľa priamo v UI (name, description, priority, estimate, story points, add/remove položiek) |
| 🔍 **Validácia** | Frontend + backend validácia pred exportom (štruktúra XML, povinné polia) |
| 📤 **Jira export** | Export celej hierarchie do Jira Cloud (Task → Sub-task) s ADF opisom, prioritou, due date, story points |
| 🔌 **Jira diagnostika** | Tlačidlo „Test Jira" — overí autentifikáciu a prístup k projektu pred exportom |
| 🌐 **i18n** | Prepínanie SK / EN v reálnom čase (frontend aj AI prompty) |
| 💾 **Perzistencia** | H2 file-based DB, automatická migrácia schémy (JPA `ddl-auto: update`) |
| 📚 **Swagger UI** | OpenAPI dokumentácia na `/swagger-ui.html` |
| 🎨 **UI animácie** | GSAP ScrollTrigger accordion, 5-farebné témy, mini-mapa navigátora, ripple efekt, typewriter titulok, stat counters |

---

## Tech Stack

- **Backend:** Java 17, Spring Boot 3.x, Spring WebFlux (WebClient), JPA / H2
- **Frontend:** Vanilla JS (ES2022), CSS custom properties, GSAP 3.12 + ScrollTrigger
- **AI:** Azure OpenAI — GPT-4o, max 8 000 tokenov na volanie
- **Jira:** Cloud REST API v3 — vytvorenie issues/subtasks, ADF description format
- **Docs:** SpringDoc OpenAPI (springdoc-openapi-starter-webmvc-ui 2.3.0)

---

## Rýchly štart

### 1. Konfigurácia (`.env` súbor)

Vytvor súbor `.env` v koreňovom adresári projektu:

```bash
AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/
AZURE_OPENAI_KEY=your-api-key
AZURE_OPENAI_DEPLOYMENT=gpt-4o

JIRA_SITE_URL=https://your-org.atlassian.net
JIRA_EMAIL=your-email@example.com
JIRA_API_TOKEN=your-jira-api-token
JIRA_PROJECT_KEY=AIP
JIRA_FIELD_STORY_POINTS=customfield_10016
```

### 2. Spustenie

```bash
set -a && source .env && set +a
mvn spring-boot:run
```

> Aplikácia beží na **http://localhost:8080**

### 3. Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Štruktúra projektu

```
src/main/
  java/org/example/
    Controller/
      ChatController.java       # REST API (generate, CRUD, export, jira/test)
    Service/
      OpenAiService.java        # Azure OpenAI volania, XML konverzia
      JiraService.java          # Jira export + pre-flight auth check
    Model/
      Project.java              # JPA entita (id, name, description, xmlContent, language, dates)
    Repository/
      ProjectRepository.java
  resources/
    static/index.html           # Celý frontend (SPA, ~2 150 riadkov)
    application.yml             # Konfigurácia (env vars)
```

---

## API Endpoints

| Metóda | URL | Popis |
|---|---|---|
| `POST` | `/api/generate` | Vygeneruje PRINCE2 + Scrum plán cez Azure OpenAI |
| `GET` | `/api/projects` | Zoznam všetkých projektov (newest first) |
| `GET` | `/api/projects/{id}` | Detail projektu |
| `PUT` | `/api/projects/{id}` | Aktualizácia projektu (inline edit) |
| `DELETE` | `/api/projects/{id}` | Zmazanie projektu |
| `POST` | `/api/projects/{id}/export-to-jira` | Export do Jira (`methodology`: PRINCE2 / Scrum) |
| `GET` | `/api/jira/test` | Test Jira pripojenia (auth + project access) |

---

## Jira Export — hierarchia

```
PRINCE2:
  Task (Stage)
    └── Subtask (Task v Stage)

Scrum:
  Task (Sprint)
    └── Task (Epic)
         └── Task (Story)
              └── Subtask (Sub-task)
```

> Poznámka: Jira Free plán nepodporuje issue type „Epic" cez API — všetky úrovne sa exportujú ako `Task` / `Subtask`.

---

## Diagnostika Jira 401

Ak export vráti `HTTP 401 UNAUTHORIZED`:

1. Klikni na tlačidlo **🔌 Test Jira** — zobrazí presný stav autentifikácie
2. Skontroluj platnosť API tokenu: [https://id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens)
3. Overiť správnosť `JIRA_EMAIL` — musí zodpovedať vlastníkovi tokenu
4. V Jira projekte: **Project settings → People** — používateľ musí mať rolu s `CREATE_ISSUES`

---

## Vývoj

```bash
# Zostaviť bez testov
mvn package -DskipTests

# Spustiť testy
mvn test

# H2 console (ak je povolená)
http://localhost:8080/h2-console
# JDBC URL: jdbc:h2:file:./data/aiplanner
```

> **Pozor:** Po zmene `src/main/resources/static/index.html` spusti:
> ```bash
> cp src/main/resources/static/index.html target/classes/static/index.html
> ```

---

## Licencia

Interné / akademické použitie (bakalárska práca). Pred verejným šírením aktualizujte podľa potreby.
