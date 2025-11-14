# AI Project Planner

AI Project Planner je full-stack aplikácia, ktorá generuje PRINCE2 a Scrum projektové plány pomocou Azure OpenAI a exportuje úlohy do existujúceho Jira projektu.

## Features
- 🎯 Single form to provide project name & description
- 🤖 Two Azure OpenAI prompts (PRINCE2 + Scrum)
- 🧱 Rich XML parsing + in-place editing before export
- 📝 Detailed descriptions converted to Jira ADF
- 🚀 One-click export to Jira Cloud (existing project key `AIP`)
- 🗂 Built-in project history (H2 DB)

## Tech Stack
- **Backend:** Java 17, Spring Boot 3, WebFlux WebClient, JPA/H2
- **Frontend:** Vanilla JS, modern CSS layout
- **AI:** Azure OpenAI (GPT-4o, 8k tokens per call)
- **Jira:** Cloud REST API v3 (issues only, no project creation)

## Getting Started
```bash
mvn spring-boot:run
# open http://localhost:8080
```

Set the following secrets in `src/main/resources/application.yml`:
```yaml
azure:
  openai:
    api-key: YOUR_AZURE_OPENAI_KEY
    endpoint: https://YOUR-RESOURCE.openai.azure.com
    deployment-name: gpt-4o

jira:
  site-url: https://YOUR-ORG.atlassian.net
  email: your-email@example.com
  api-token: YOUR_JIRA_API_TOKEN
  project-key: AIP
```

## Jira Export Flow
1. User clicks *Export PRINCE2* or *Export Scrum*
2. Backend parses stored XML
3. Creates Issues/Sub-tasks via Jira REST `/rest/api/3/issue`
4. Same project key (`AIP`), URL returned to UI

## XML Editing
- Every project snapshot is saved to DB (H2)
- Entire XML is editable in textarea
- Stage/Sprint titles & descriptions are inline editable (`contenteditable`)
- Saving updates XML + persists immediately (PUT `/api/projects/{id}`)

## Logs & Debugging
- Azure prompts/responses stored under `logs/request_*.log`
- Export errors surfaced in UI + backend logs
- Frontend highlights parsing issues and raw XML when needed

## License
Internal/academic use. Update as needed before public distribution.

