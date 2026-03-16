# Changelog — AI Project Planner

Všetky významné zmeny sú dokumentované tu. Projekt je vyvíjaný ako bakalárska práca.

---

## [Unreleased] — aktuálna verzia

### Opravené
- **Jira export 401 UNAUTHORIZED** — pridaný pre-flight auth check (`/rest/api/3/myself`) pred exportom; pri neplatnom tokene sa export ihneď preruší s jednou jasnou chybou namiesto N chýb (jedna za každú úlohu)
- **Jira export — early abort** — `sendJiraCreate` deteguje 401/403 a nastaví príznak `abortExport`; zvyšné úlohy sa preskočia

### Pridané
- **`GET /api/jira/test`** — nový endpoint pre diagnostiku Jira pripojenia (autentifikácia + existencia projektu)
- **Tlačidlo „🔌 Test Jira"** — v hlavičke detailu projektu; volá `/api/jira/test` a zobrazí výsledok ako toast

### Animácie a UI (PRINCE2 / Scrum karty)
- **Vertikálna živá línia** — `acc-vline` s GSAP `scrub:1.2` ScrollTrigger; kreslí sa pozdĺž zoznamu fáz/sprintov pri scrollovaní
- **Plávajúca mini-mapa** — fixovaný navigátor vpravo; jedna bodka na fázu/sprint; klik scrolluje na accordion; aktívna bodka sa zväčší a svieti farbou témy
- **Animácia štatistík (Stat counters)** — pri otvorení accordionu číslice v `acc-chip` naskočia od 0 po skutočnú hodnotu (GSAP `power2.out`)
- **Ripple efekt** — pri kliknutí na hlavičku accordionu sa rozíde kruh v téme farby (GSAP `fromTo` scale + fade)
- **Typewriter titul** — pri prvom otvorení accordionu sa text nadpisu vypíše znak po znaku s blikajúcim kurzorom

### Dashboard
- Všetky texty preložené cez `t()` — žiadny hardcoded text (SK + EN)
- 5-farebné témy accordionov cyklicky: modrá → zelená → fialová → žltá → tyrkysová
- Polopriesvitné pozadia panelov: `.dp-p2` (modrá), `.dp-sc` (zelená), `.dp-risk` (žltá)
- IntersectionObserver scroll-reveal animácie pre riadky, panely, timeline nodes, risk blocks

---

## [0.5.0] — Dashboard V2 + Porovnávacia tabuľka

### Pridané
- **Interaktívny dashboard** s porovnaním PRINCE2 vs Scrum
  - Radar chart (SVG, 5 osí: Fázy, Úlohy, Čas, Riziká, Priorita) — normalizovaný podľa skutočných hodnôt projektu
  - Donut grafy priorít (High / Medium / Low) pre obe metodológie
  - Sprint velocity bars (stories + story points na sprint)
  - Časová os fáz (PRINCE2) a sprintov (Scrum)
  - Porovnávacia tabuľka: štruktúra, časová náročnosť, riziká & priority
  - Risk overview bloky
- **Oprava dvojitého počítania PRINCE2** — estimate a priorita sa počítajú len na úrovni `Task` (nie `Stage`); porovnateľné s `Story` v Scrum

### Zmenené
- Sprint velocity bary: sprints pomenované „Sprint 1", „Sprint 2"… (nie plný názov)
- Odstrihnutý horný riadok metric kariet (Fazy, Ulohy, Rizika, Sprinty, Stories, Story Points)

---

## [0.4.0] — i18n + Inline edit + Validácia

### Pridané
- **Jazykový prepínač SK / EN** — sidebar header; `localStorage` pamätá voľbu
- **`t(key)` / `tf(key, ...args)`** — kompletný i18n systém, ~50 kľúčov v SK aj EN
- **AI prompty** — generovanie v slovenčine aj angličtine podľa `lang` parametra
- **`language` pole** v `Project` entite (JPA stĺpec, default `"sk"`)
- **Inline editovanie** — edit mode prepína na editovateľné polia (input/textarea/select) priamo v PRINCE2/Scrum kartách; add/remove stage, task, sprint, epic, story, subtask, criterion, output, risk
- **Validácia formulára** — frontend: name (min 3), description (min 10), dátumy (start < deadline)
- **Validácia pred exportom** — kontrola XML štruktúry (Stages/Sprints, názvy); pri chybách modal s možnosťou pokračovať

### Zmenené
- `PUT /api/projects/{id}` — akceptuje `name`, `description`, `xmlContent`, `startDate`, `deadline`

---

## [0.3.0] — Swagger + Persistencia

### Pridané
- **SpringDoc OpenAPI** — `springdoc-openapi-starter-webmvc-ui 2.3.0`
- `@Tag`, `@Operation`, `@ApiResponse` anotácie na `ChatController`
- Swagger UI dostupné na `/swagger-ui.html`, API docs na `/api-docs`
- **H2 file-based databáza** — `jdbc:h2:file:./data/aiplanner`; projekty prežijú reštart
- **`Project` JPA entita** — polia: id, name, description, xmlContent, startDate, deadline, createdAt, updatedAt
- **`ProjectRepository`** — `findAllByOrderByIdDesc()`
- **CRUD endpoints** — `GET /api/projects`, `GET /api/projects/{id}`, `PUT /api/projects/{id}`, `DELETE /api/projects/{id}`

---

## [0.2.0] — Jira Export + XML parsing

### Pridané
- **`JiraService`** — export do Jira Cloud REST API v3
  - PRINCE2: Stage → Task, Task v Stage → Subtask
  - Scrum: Sprint → Task, Epic → Task, Story → Task, Sub-task → Subtask
  - ADF (Atlassian Document Format) konverzia popisu
  - Retry logika — pri 400 skúsi bez `component`/`priority`/`timetracking`
  - `getDirectText()` — číta len priameho potomka (nie rekurzívne)
  - `filterDirectChildren()` — filtruje elementy podľa parent tag
- **Jira link** v UI — po úspešnom exporte zobrazí odkaz na Jira projekt (localStorage)
- Deduplicita exportov — `exportInProgress` set zabraňuje súbežným exportom toho istého projektu

### Opravené
- XML parsing — extrakcia `<ProjectPlans>` sekcie (case-insensitive), fallback na celý obsah

---

## [0.1.0] — Základný generátor

### Pridané
- **Spring Boot** projekt (Java 17, Maven)
- **`OpenAiService`** — dual-prompt generovanie (PRINCE2 + Scrum) cez Azure OpenAI WebClient
  - `buildPrince2Prompt()` — PRINCE2 štruktúra (ProjectPlans > PRINCE2Project > Stages > Stage > Tasks/Outputs/Risks)
  - `buildScrumSprintPrompt()` — Scrum štruktúra (ScrumProject > Sprints > Sprint > Epics > Epic > Stories > Story > SubTasks)
  - `calculateSprintDates()` — rovnomerné rozdelenie dátumov sprintov medzi start a deadline
  - `extractJsonBlock()` — extrakcia JSON z markdown code blocku
  - `convertPrince2JsonToXml()` / `convertScrumSprintsToXml()` — JSON → XML konverzia
- **`POST /api/generate`** — vstup: name, description, startDate, deadline → výstup: XML
- **Vanilla JS SPA** (`index.html`) — formulár, sidebar so zoznamom projektov, detailný pohľad s tabmi (PRINCE2, Scrum)
- **Accordion UI** — fázy a sprinty v skladacích kartách
- **Loading overlay** — animovaný spinner s logom krokov počas generovania
- **Toast notifikácie** — success/error/info
- **`application.yml`** — konfigurácia cez environment premenné (Azure OpenAI, Jira)

---

## Poznámky k nasadeniu

### Spustenie so `.env`
```bash
set -a && source .env && set +a && nohup mvn spring-boot:run > app.log 2>&1 &
```

### Dôležité: statické súbory
Spring Boot v dev móde (`spring-boot:run`) servíruje statické súbory z `target/classes/static/`, nie zo `src/main/resources/static/`. Po každej zmene `index.html` spusti:
```bash
cp src/main/resources/static/index.html target/classes/static/index.html
```

### H2 databáza
Ak server nevie štartovať kvôli zamknutej DB:
```bash
pkill -9 -f "java"
# počkaj 3-4 sekundy, potom reštartuj
```
