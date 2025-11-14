package org.example.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    
    private final WebClient webClient;
    private final String deploymentName;
    private static final String LOG_DIRECTORY = "logs";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public OpenAiService(
        @Value("${azure.openai.api-key}") String apiKey,
        @Value("${azure.openai.endpoint}") String endpoint,
        @Value("${azure.openai.deployment-name}") String deploymentName
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(endpoint)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.deploymentName = deploymentName;
        
        log.info("OpenAiService initialized with endpoint: {}", endpoint);
    }

    public String generateProjectPlan(String projectName, String projectDescription) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Odosielam požiadavku do Azure OpenAI pre projekt: {}", projectName);

        String princePrompt = buildPrince2Prompt(projectName, projectDescription);
        String princeLog = String.format("request_%s_prince2.log", timestamp);
        String princeResponse = callOpenAi(princePrompt, princeLog);
        String princeXml = extractXmlSection(princeResponse, "PRINCE2Project");

        String scrumPrompt = buildScrumPrompt(projectName, projectDescription);
        String scrumLog = String.format("request_%s_scrum.log", timestamp);
        String scrumResponse = callOpenAi(scrumPrompt, scrumLog);
        String scrumXml = extractXmlSection(scrumResponse, "ScrumProject");

        String combinedXml = "<ProjectPlans>\n" + princeXml + "\n\n" + scrumXml + "\n</ProjectPlans>";

        logToFile(String.format("request_%s_combined.log", timestamp), "=== COMBINED_XML ===", combinedXml);

        return combinedXml;
    }

    private String buildPrince2Prompt(String projectName, String projectDescription) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return String.format("""
                Si certifikovaný PRINCE2 projektový manažér. Vráť iba XML dokument s koreňom <PRINCE2Project>. Žiadny text mimo XML, žiadny Markdown, žiadne CDATA.

                Vstupné údaje:
                - Názov projektu: %s
                - Popis projektu: %s

                Použi presnú štruktúru (iba polia, ktoré sa prenášajú do Jira):
                <PRINCE2Project>
                  <Name>(stručný názov projektu)</Name>
                  <Description>(hlavný cieľ a prínos projektu)</Description>
                  <Stages>
                    <Stage>
                      <StageID>1</StageID>
                      <Name>(názov fázy - použije sa ako summary v Jira)</Name>
                      <Description>(opis práce vo fáze - použije sa v description v Jira)</Description>
                      <DueDate>YYYY-MM-DD</DueDate>
                      <Priority>High|Medium|Low</Priority>
                      <Assignee>meno.priezvisko@example.com</Assignee>
                      <Labels>tag1,tag2,tag3</Labels>
                      <Outputs>
                        <Output>(merateľný výstup fázy - použije sa v description)</Output>
                        <Output>(ďalší výstup fázy)</Output>
                      </Outputs>
                      <Risks>
                        <Risk>(riziko a plán mitigácie - použije sa v description)</Risk>
                        <Risk>(ďalšie riziko a mitigácia)</Risk>
                      </Risks>
                      <Tasks>
                        <Task>
                          <Title>(názov úlohy - použije sa ako summary sub-tasku v Jira)</Title>
                          <Description>(čo presne treba urobiť - použije sa v description sub-tasku)</Description>
                          <Priority>High|Medium|Low</Priority>
                          <DueDate>YYYY-MM-DD</DueDate>
                          <Assignee>meno.priezvisko@example.com</Assignee>
                          <Labels>tag1,tag2</Labels>
                        </Task>
                        <Task>
                          <Title>(názov úlohy)</Title>
                          <Description>(čo presne treba urobiť)</Description>
                          <Priority>High|Medium|Low</Priority>
                          <DueDate>YYYY-MM-DD</DueDate>
                          <Assignee>meno.priezvisko@example.com</Assignee>
                          <Labels>tag1,tag2</Labels>
                        </Task>
                        <Task>
                          <Title>(názov úlohy)</Title>
                          <Description>(čo presne treba urobiť)</Description>
                          <Priority>High|Medium|Low</Priority>
                          <DueDate>YYYY-MM-DD</DueDate>
                          <Assignee>meno.priezvisko@example.com</Assignee>
                          <Labels>tag1,tag2</Labels>
                        </Task>
                      </Tasks>
                    </Stage>
                  </Stages>
                </PRINCE2Project>

                Dôležité: Tieto polia sa prenášajú do Jira:
                - Name → summary (názov úlohy v Jira)
                - Description → description (popis úlohy v Jira)
                - DueDate → duedate (termín dokončenia)
                - Labels → labels (štítky)
                - Outputs, Risks → súčasť description (textovo)
                - Tasks → vytvorené ako sub-tasky s rodičovskou úlohou

                Povinné pravidlá:
                1. Presne 4 fázy <Stage> so StageID 1..4.
                2. DueDate musí byť >= %s a vo formáte YYYY-MM-DD.
                3. Každá fáza obsahuje minimálne 2 <Output>, 2 <Risk> a 3 <Task>.
                4. Všetky texty píš po slovensky, bez placeholderov („...", „TBD", „???").
                5. Emaily používaj vo formáte meno.priezvisko@example.com.
                """, projectName, projectDescription, today);
    }

    private String buildScrumPrompt(String projectName, String projectDescription) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return String.format("""
                Si skúsený Scrum master. Vráť iba XML dokument s koreňom <ScrumProject>. Žiadny text mimo XML, žiadny Markdown, žiadne CDATA.

                Vstupné údaje:
                - Názov projektu: %s
                - Popis projektu: %s

                Štruktúra (iba polia, ktoré sa prenášajú do Jira):
                <ScrumProject>
                  <Name>(stručný názov projektu)</Name>
                  <Description>(popis produktu a cieľa)</Description>
                  <Sprints>
                    <Sprint>
                      <SprintID>1</SprintID>
                      <Name>(názov šprintu - použije sa ako summary v Jira)</Name>
                      <Goal>(konkrétny cieľ šprintu - použije sa v description)</Goal>
                      <Description>(stručný plán práce - použije sa v description)</Description>
                      <DueDate>YYYY-MM-DD</DueDate>
                      <Priority>High|Medium|Low</Priority>
                      <Assignee>meno.priezvisko@example.com</Assignee>
                      <Labels>tag1,tag2</Labels>
                      <Risks>
                        <Risk>(riziko a plán mitigácie - použije sa v description)</Risk>
                        <Risk>(ďalšie riziko a mitigácia)</Risk>
                      </Risks>
                      <Epics>
                        <Epic>
                          <EpicID>(identifikátor epiku)</EpicID>
                          <Title>(názov epiku - použije sa ako summary v Jira)</Title>
                          <Description>(opis biznis hodnoty - použije sa v description)</Description>
                          <Priority>High|Medium|Low</Priority>
                          <Assignee>meno.priezvisko@example.com</Assignee>
                          <Labels>tag1,tag2</Labels>
                          <Stories>
                            <Story>
                              <ID>(unikátne ID)</ID>
                              <Title>(názov user story - použije sa ako summary v Jira)</Title>
                              <Description>(opis potreby používateľa - použije sa v description)</Description>
                              <StoryPoints>číslo</StoryPoints>
                              <Priority>High|Medium|Low</Priority>
                              <DueDate>YYYY-MM-DD</DueDate>
                              <Assignee>meno.priezvisko@example.com</Assignee>
                              <Labels>tag1,tag2</Labels>
                              <AcceptanceCriteria>
                                <Criterion>(kritérium úspechu - použije sa v description)</Criterion>
                                <Criterion>(ďalšie kritérium)</Criterion>
                                <Criterion>(tretie kritérium)</Criterion>
                              </AcceptanceCriteria>
                              <SubTasks>
                                <Task>
                                  <Title>(názov sub-tasku - použije sa ako summary sub-tasku v Jira)</Title>
                                  <Description>(čo treba urobiť - použije sa v description sub-tasku)</Description>
                                  <Priority>High|Medium|Low</Priority>
                                  <Assignee>meno.priezvisko@example.com</Assignee>
                                </Task>
                                <Task>
                                  <Title>(názov sub-tasku)</Title>
                                  <Description>(čo treba urobiť)</Description>
                                  <Priority>High|Medium|Low</Priority>
                                  <Assignee>meno.priezvisko@example.com</Assignee>
                                </Task>
                              </SubTasks>
                            </Story>
                          </Stories>
                        </Epic>
                      </Epics>
                    </Sprint>
                  </Sprints>
                </ScrumProject>

                Dôležité: Tieto polia sa prenášajú do Jira:
                - Name/Title → summary (názov úlohy v Jira)
                - Description → description (popis úlohy v Jira)
                - DueDate → duedate (termín dokončenia)
                - Labels → labels (štítky)
                - Goal, Risks → súčasť description (textovo)
                - AcceptanceCriteria → súčasť description (textovo)
                - SubTasks → vytvorené ako sub-tasky s rodičovskou úlohou

                Povinné pravidlá:
                1. Presne 3 šprinty s ID 1, 2, 3.
                2. Každý šprint obsahuje minimálne 2 epiky a každý epic 2 alebo 3 user stories.
                3. Každá story má min. 3 <Criterion> a 2 <Task> v sekcii <SubTasks>.
                4. Všetky texty píš po slovensky, bez placeholderov („...", „TBD", „???").
                5. DueDate musí byť >= %s a vo formáte YYYY-MM-DD.
                6. Všetky emaily používaj vo formáte meno.priezvisko@example.com.
                """, projectName, projectDescription, today);
    }

    private String callOpenAi(String prompt, String logFileName) {
        logToFile(logFileName, "=== REQUEST ===", prompt);

        Map<String, Object> body = Map.of(
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 8000
        );

        try {
            Map<String, Object> response = webClient.post()
                .uri("/openai/deployments/" + deploymentName + "/chat/completions?api-version=2025-01-01-preview")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(java.time.Duration.ofSeconds(120))
                .block();

            if (response == null) {
                throw new RuntimeException("OpenAI vrátil prázdnu odpoveď.");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("OpenAI odpoveď neobsahuje choices.");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || !message.containsKey("content")) {
                throw new RuntimeException("OpenAI odpoveď neobsahuje výsledný obsah.");
            }

            String content = ((String) message.get("content")).trim();

            logToFile(logFileName, "=== RESPONSE ===", content);
            logMetadata(logFileName, response);

            return content;
        } catch (Exception e) {
            log.error("Chyba pri požiadavke na Azure OpenAI: {}", e.getMessage(), e);
            logToFile(logFileName, "=== ERROR ===", e.getMessage() + "\n" + e.getClass().getName());
            throw new RuntimeException("Chyba pri komunikácii s OpenAI: " + e.getMessage(), e);
        }
    }

    private String extractXmlSection(String content, String rootTag) {
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("OpenAI vrátil prázdny obsah pre " + rootTag);
        }

        String trimmed = content.trim();
        Pattern pattern = Pattern.compile(
            String.format("(?is)<%1$s\\b.*?</%1$s>", rootTag)
        );
        Matcher matcher = pattern.matcher(trimmed);
        if (!matcher.find()) {
            throw new RuntimeException("Výstup neobsahuje očakávaný tag <" + rootTag + ">.");
        }

        return matcher.group().trim();
    }

    private String generateProjectPlanLegacy(String projectName, String projectDescription) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        String logFileName = String.format("request_%s.log", timestamp);
        
        log.info("Odosielam požiadavku do Azure OpenAI pre projekt: {}", projectName);

        String prompt = "Si expert na riadenie IT projektov. Vygeneruj PODROBNÝ XML pre Jira export s VŠETKÝMI potrebnými poľami.\n\n" +
            "📋 PRESNÝ FORMÁT PRE JIRA (BEZ MARKDOWN, BEZ CDATA):\n\n" +
            "<ProjectPlans>\n" +
            "  <PRINCE2Project>\n" +
            "    <Name>" + projectName + "</Name>\n" +
            "    <Stages>\n" +
            "      <Stage>\n" +
            "        <StageID>1</StageID>\n" +
            "        <Name>Názov fázy 1</Name>\n" +
            "        <Description>Podrobný popis fázy pre Jira</Description>\n" +
            "        <StartDate>2025-11-01</StartDate>\n" +
            "        <EndDate>2025-11-15</EndDate>\n" +
            "        <DueDate>2025-11-15</DueDate>\n" +
            "        <Budget>5000</Budget>\n" +
            "        <Priority>High</Priority>\n" +
            "        <Assignee>email@example.com</Assignee>\n" +
            "        <Labels>prince2,stage,planning</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup projektu 1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup projektu 2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika</Description>\n" +
            "            <Impact>Vysoký</Impact>\n" +
            "            <Probability>Stredná</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Názov kontrolného bodu</Name>\n" +
            "            <Date>2025-11-10</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Rola účastníka</Role>\n" +
            "            <Name>Meno účastníka</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StageID>1</StageID>\n" +
            "            <Description>Popis závislosti</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Stage>\n" +
            "      <Stage>\n" +
            "        <StageID>2</StageID>\n" +
            "        <Name>Názov fázy 2</Name>\n" +
            "        <Description>Podrobný popis fázy pre Jira</Description>\n" +
            "        <StartDate>2025-11-16</StartDate>\n" +
            "        <EndDate>2025-11-30</EndDate>\n" +
            "        <DueDate>2025-11-30</DueDate>\n" +
            "        <Budget>6000</Budget>\n" +
            "        <Priority>Medium</Priority>\n" +
            "        <Assignee>developer@example.com</Assignee>\n" +
            "        <Labels>prince2,stage,development</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup fázy 2-1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup fázy 2-2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika fázy 2</Description>\n" +
            "            <Impact>Stredný</Impact>\n" +
            "            <Probability>Nízka</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Kontrolný bod fázy 2</Name>\n" +
            "            <Date>2025-11-25</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Developer</Role>\n" +
            "            <Name>Meno účastníka 2</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StageID>1</StageID>\n" +
            "            <Description>Závislosť na fáze 1</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Stage>\n" +
            "      <Stage>\n" +
            "        <StageID>3</StageID>\n" +
            "        <Name>Názov fázy 3</Name>\n" +
            "        <Description>Podrobný popis fázy pre Jira</Description>\n" +
            "        <StartDate>2025-12-01</StartDate>\n" +
            "        <EndDate>2025-12-15</EndDate>\n" +
            "        <DueDate>2025-12-15</DueDate>\n" +
            "        <Budget>7000</Budget>\n" +
            "        <Priority>High</Priority>\n" +
            "        <Assignee>tester@example.com</Assignee>\n" +
            "        <Labels>prince2,stage,testing</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup fázy 3-1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup fázy 3-2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika fázy 3</Description>\n" +
            "            <Impact>Vysoký</Impact>\n" +
            "            <Probability>Vysoká</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Kontrolný bod fázy 3</Name>\n" +
            "            <Date>2025-12-10</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Tester</Role>\n" +
            "            <Name>Meno účastníka 3</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StageID>2</StageID>\n" +
            "            <Description>Závislosť na fáze 2</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Stage>\n" +
            "    </Stages>\n" +
            "  </PRINCE2Project>\n" +
            "  <ScrumProject>\n" +
            "    <Name>" + projectName + "</Name>\n" +
            "    <Sprints>\n" +
            "      <Sprint>\n" +
            "        <SprintID>1</SprintID>\n" +
            "        <Name>Sprint 1</Name>\n" +
            "        <Goal>Cieľ šprintu 1</Goal>\n" +
            "        <Description>Podrobný popis šprintu pre Jira</Description>\n" +
            "        <StartDate>2025-11-01</StartDate>\n" +
            "        <EndDate>2025-11-15</EndDate>\n" +
            "        <DueDate>2025-11-15</DueDate>\n" +
            "        <Capacity>40</Capacity>\n" +
            "        <Priority>High</Priority>\n" +
            "        <Assignee>email@example.com</Assignee>\n" +
            "        <Labels>scrum,sprint,agile</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika pre šprint</Description>\n" +
            "            <Impact>Vysoký</Impact>\n" +
            "            <Probability>Stredná</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Názov kontrolného bodu šprintu</Name>\n" +
            "            <Date>2025-11-10</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Scrum Master</Role>\n" +
            "            <Name>Meno účastníka</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "      </Sprint>\n" +
            "      <Sprint>\n" +
            "        <SprintID>2</SprintID>\n" +
            "        <Name>Sprint 2</Name>\n" +
            "        <Goal>Cieľ šprintu 2</Goal>\n" +
            "        <Description>Podrobný popis šprintu pre Jira</Description>\n" +
            "        <StartDate>2025-11-16</StartDate>\n" +
            "        <EndDate>2025-11-30</EndDate>\n" +
            "        <DueDate>2025-11-30</DueDate>\n" +
            "        <Capacity>40</Capacity>\n" +
            "        <Priority>Medium</Priority>\n" +
            "        <Assignee>scrum.master@example.com</Assignee>\n" +
            "        <Labels>scrum,sprint,agile</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 2-1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 2-2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika pre šprint 2</Description>\n" +
            "            <Impact>Stredný</Impact>\n" +
            "            <Probability>Nízka</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Kontrolný bod šprintu 2</Name>\n" +
            "            <Date>2025-11-25</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Product Owner</Role>\n" +
            "            <Name>Meno účastníka 2</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "      </Sprint>\n" +
            "      <Sprint>\n" +
            "        <SprintID>3</SprintID>\n" +
            "        <Name>Sprint 3</Name>\n" +
            "        <Goal>Cieľ šprintu 3</Goal>\n" +
            "        <Description>Podrobný popis šprintu pre Jira</Description>\n" +
            "        <StartDate>2025-12-01</StartDate>\n" +
            "        <EndDate>2025-12-15</EndDate>\n" +
            "        <DueDate>2025-12-15</DueDate>\n" +
            "        <Capacity>40</Capacity>\n" +
            "        <Priority>High</Priority>\n" +
            "        <Assignee>developer@example.com</Assignee>\n" +
            "        <Labels>scrum,sprint,agile</Labels>\n" +
            "        <Deliverables>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 3-1</Deliverable>\n" +
            "          <Deliverable>Kľúčový výstup šprintu 3-2</Deliverable>\n" +
            "        </Deliverables>\n" +
            "        <Risks>\n" +
            "          <Risk>\n" +
            "            <Description>Popis rizika pre šprint 3</Description>\n" +
            "            <Impact>Vysoký</Impact>\n" +
            "            <Probability>Vysoká</Probability>\n" +
            "            <Mitigation>Plán opatrení na zmiernenie rizika</Mitigation>\n" +
            "          </Risk>\n" +
            "        </Risks>\n" +
            "        <Milestones>\n" +
            "          <Milestone>\n" +
            "            <Name>Kontrolný bod šprintu 3</Name>\n" +
            "            <Date>2025-12-10</Date>\n" +
            "          </Milestone>\n" +
            "        </Milestones>\n" +
            "        <Stakeholders>\n" +
            "          <Stakeholder>\n" +
            "            <Role>Developer</Role>\n" +
            "            <Name>Meno účastníka 3</Name>\n" +
            "          </Stakeholder>\n" +
            "        </Stakeholders>\n" +
            "      </Sprint>\n" +
            "    </Sprints>\n" +
            "    <UserStories>\n" +
            "      <Story>\n" +
            "        <ID>US-1</ID>\n" +
            "        <Title>User Story názov 1</Title>\n" +
            "        <Description>Podrobný popis user story pre Jira</Description>\n" +
            "        <Estimate>5</Estimate>\n" +
            "        <Priority>High</Priority>\n" +
            "        <DueDate>2025-11-10</DueDate>\n" +
            "        <Assignee>email@example.com</Assignee>\n" +
            "        <Labels>user-story,frontend,feature</Labels>\n" +
            "        <AcceptanceCriteria>\n" +
            "          <Criterion>Kritérium prijatia 1</Criterion>\n" +
            "          <Criterion>Kritérium prijatia 2</Criterion>\n" +
            "        </AcceptanceCriteria>\n" +
            "        <Tasks>\n" +
            "          <Task>\n" +
            "            <Description>Popis úlohy</Description>\n" +
            "            <Estimate>2</Estimate>\n" +
            "          </Task>\n" +
            "        </Tasks>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StoryID>US-2</StoryID>\n" +
            "            <Description>Popis závislosti</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Story>\n" +
            "      <Story>\n" +
            "        <ID>US-2</ID>\n" +
            "        <Title>User Story názov 2</Title>\n" +
            "        <Description>Podrobný popis user story pre Jira</Description>\n" +
            "        <Estimate>8</Estimate>\n" +
            "        <Priority>Medium</Priority>\n" +
            "        <DueDate>2025-11-20</DueDate>\n" +
            "        <Assignee>developer@example.com</Assignee>\n" +
            "        <Labels>user-story,backend,feature</Labels>\n" +
            "        <AcceptanceCriteria>\n" +
            "          <Criterion>Kritérium prijatia 2-1</Criterion>\n" +
            "          <Criterion>Kritérium prijatia 2-2</Criterion>\n" +
            "        </AcceptanceCriteria>\n" +
            "        <Tasks>\n" +
            "          <Task>\n" +
            "            <Description>Popis úlohy 2-1</Description>\n" +
            "            <Estimate>3</Estimate>\n" +
            "          </Task>\n" +
            "          <Task>\n" +
            "            <Description>Popis úlohy 2-2</Description>\n" +
            "            <Estimate>2</Estimate>\n" +
            "          </Task>\n" +
            "        </Tasks>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StoryID>US-1</StoryID>\n" +
            "            <Description>Závislosť na US-1</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Story>\n" +
            "      <Story>\n" +
            "        <ID>US-3</ID>\n" +
            "        <Title>User Story názov 3</Title>\n" +
            "        <Description>Podrobný popis user story pre Jira</Description>\n" +
            "        <Estimate>13</Estimate>\n" +
            "        <Priority>High</Priority>\n" +
            "        <DueDate>2025-12-05</DueDate>\n" +
            "        <Assignee>tester@example.com</Assignee>\n" +
            "        <Labels>user-story,testing,feature</Labels>\n" +
            "        <AcceptanceCriteria>\n" +
            "          <Criterion>Kritérium prijatia 3-1</Criterion>\n" +
            "          <Criterion>Kritérium prijatia 3-2</Criterion>\n" +
            "          <Criterion>Kritérium prijatia 3-3</Criterion>\n" +
            "        </AcceptanceCriteria>\n" +
            "        <Tasks>\n" +
            "          <Task>\n" +
            "            <Description>Popis úlohy 3-1</Description>\n" +
            "            <Estimate>5</Estimate>\n" +
            "          </Task>\n" +
            "          <Task>\n" +
            "            <Description>Popis úlohy 3-2</Description>\n" +
            "            <Estimate>3</Estimate>\n" +
            "          </Task>\n" +
            "        </Tasks>\n" +
            "        <Dependencies>\n" +
            "          <Dependency>\n" +
            "            <StoryID>US-2</StoryID>\n" +
            "            <Description>Závislosť na US-2</Description>\n" +
            "          </Dependency>\n" +
            "        </Dependencies>\n" +
            "      </Story>\n" +
            "    </UserStories>\n" +
            "  </ScrumProject>\n" +
            "</ProjectPlans>\n\n" +
            "⚠️ DÔLEŽITÉ PRAVIDLÁ:\n" +
            "1. ŽIADNY Markdown (```xml), žiadne CDATA\n" +
            "2. Pre PRINCE2: vytvor niekoľko Stage (3-5) s VŠETKÝMI poľami\n" +
            "   - Každá Stage MUSÍ obsahovať:\n" +
            "     * Deliverables: 2-3 kľúčové výstupy fázy\n" +
            "     * Risks: 1-2 riziká s popisom, impactom (Vysoký/Stredný/Nízky), pravdepodobnosťou (Vysoká/Stredná/Nízka) a plánom zmiernenia\n" +
            "     * Milestones: 1-2 kontrolné body s dátumom\n" +
            "     * Stakeholders: 2-3 účastníkov s rolou a menom\n" +
            "     * Dependencies: opcionalne - závislosti na iných fázach (StageID a popis)\n" +
            "3. Pre Scrum: vytvor niekoľko šprintov (2-3) a user stories (5-8) s VŠETKÝMI poľami\n" +
            "   - Každý Sprint MUSÍ obsahovať:\n" +
            "     * Deliverables: 2-3 kľúčové výstupy šprintu\n" +
            "     * Risks: 1-2 riziká s popisom, impactom (Vysoký/Stredný/Nízky), pravdepodobnosťou (Vysoká/Stredná/Nízka) a plánom zmiernenia\n" +
            "     * Milestones: 1-2 kontrolné body s dátumom\n" +
            "     * Stakeholders: 2-3 účastníkov s rolou (Scrum Master, Product Owner, Developer) a menom\n" +
            "   - Každá User Story MUSÍ obsahovať:\n" +
            "     * AcceptanceCriteria: 2-3 kritériá prijatia\n" +
            "     * Tasks: 2-3 konkrétnych úloh s popisom a estimate (story points)\n" +
            "     * Dependencies: opcionalne - závislosti na iných user stories (StoryID a popis)\n" +
            "4. Všetky texty v slovenčine\n" +
            "5. Dátumy od " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "\n" +
            "6. Budget realistický v EUR\n" +
            "7. Priority: High, Medium, Low\n" +
            "8. Assignee: email adresa (napr. developer@example.com) alebo prázdne ak nie je\n" +
            "9. Labels: zoznam oddelený čiarkou (napr. frontend,backend,urgent)\n" +
            "10. DueDate: dátum v formáte YYYY-MM-DD (musí byť >= StartDate)\n" +
            "11. VŽDY ZATVOR VŠETKY TAGY!\n" +
            "12. Pre každú PRINCE2 Stage vyplň VŠETKY nové sekcie (Deliverables, Risks, Milestones, Stakeholders) - nechaj Dependencies prázdne ak nie sú potrebné\n" +
            "13. Pre každý Scrum Sprint vyplň VŠETKY nové sekcie (Deliverables, Risks, Milestones, Stakeholders)\n" +
            "14. Pre každú User Story vyplň VŠETKY nové sekcie (AcceptanceCriteria, Tasks) - nechaj Dependencies prázdne ak nie sú potrebné\n\n" +
            "Popis projektu: " + projectDescription + "\n\n" +
            "🚨 KRÍTICKÉ POŽIADAVKY:\n" +
            "1. Tvoja odpoveď MUSÍ začínať PRESNE s <ProjectPlans> (bez akýchkoľvek znakov predtým)\n" +
            "2. Tvoja odpoveď MUSÍ končiť PRESNE s </ProjectPlans> (bez akýchkoľvek znakov potom)\n" +
            "3. ŽIADNY text pred XML alebo po XML - IBA XML\n" +
            "4. VŠETKY XML tagy MUSIA byť správne uzatvorené\n" +
            "5. XML MUSÍ byť úplný a validný - žiadne obrezanie\n\n" +
            "Vygeneruj PODROBNÝ XML s VŠETKÝMI poľami potrebnými pre Jira. Odpoveď MUSÍ byť IBA XML bez akýchkoľvek ďalších znakov!";

        logToFile(logFileName, "=== REQUEST ===", prompt);

        Map<String, Object> body = Map.of(
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 16000
        );

        try {
            Map response = webClient.post()
                .uri("/openai/deployments/" + deploymentName + "/chat/completions?api-version=2025-01-01-preview")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(120))
                .block();

            String content;
            var choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            content = (String) message.get("content");
            
            // Логируем ответ
            logToFile(logFileName, "=== RESPONSE ===", content);
            logMetadata(logFileName, response);
            
            return content;
            
        } catch (Exception e) {
            log.error("Chyba pri požiadavke na Azure OpenAI: {}", e.getMessage(), e);
            logToFile(logFileName, "=== ERROR ===", e.getMessage() + "\n" + e.getClass().getName());
            
            if (e.getCause() instanceof java.util.concurrent.TimeoutException || 
                e.getClass().getName().contains("Timeout")) {
                return "Chyba: Požiadavka trvá príliš dlho. Skúste to znova.";
            }
            
            return "Chyba: " + e.getMessage();
        }
    }

    private void logToFile(String fileName, String section, String content) {
        try {
            Path logDir = Paths.get(LOG_DIRECTORY);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            
            Path logFile = logDir.resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write("\n" + "=".repeat(80) + "\n");
                writer.write(section + "\n");
                writer.write("Time: " + LocalDateTime.now() + "\n");
                writer.write("=".repeat(80) + "\n");
                writer.write(content + "\n");
            }
            
            log.info("Logged to file: {}", logFile.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write log file: {}", e.getMessage());
        }
    }

    private void logMetadata(String fileName, Map response) {
        try {
            StringBuilder metadata = new StringBuilder();
            metadata.append("\n=== METADATA ===\n");
            
            if (response.containsKey("usage")) {
                Map usage = (Map) response.get("usage");
                metadata.append("Tokens used:\n");
                metadata.append("  - Prompt tokens: ").append(usage.get("prompt_tokens")).append("\n");
                metadata.append("  - Completion tokens: ").append(usage.get("completion_tokens")).append("\n");
                metadata.append("  - Total tokens: ").append(usage.get("total_tokens")).append("\n");
            }
            
            if (response.containsKey("model")) {
                metadata.append("Model: ").append(response.get("model")).append("\n");
            }
            
            if (response.containsKey("id")) {
                metadata.append("Request ID: ").append(response.get("id")).append("\n");
            }
            
            Path logFile = Paths.get(LOG_DIRECTORY).resolve(fileName);
            try (FileWriter writer = new FileWriter(logFile.toFile(), true)) {
                writer.write(metadata.toString());
                writer.write("\n" + "=".repeat(80) + "\n");
            }
        } catch (IOException e) {
            log.error("Failed to write metadata: {}", e.getMessage());
        }
    }

    /**
     * Извлекает XML из ответа OpenAI, удаляя текст перед XML
     * @param response полный ответ от OpenAI
     * @return извлеченный XML или исходный ответ, если XML не найден
     */
    private String extractXmlFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return response;
        }
        
        // Ищем начало XML тега <ProjectPlans>
        int xmlStart = response.indexOf("<ProjectPlans>");
        if (xmlStart == -1) {
            // Если не нашли, пробуем найти без учета регистра
            String lowerResponse = response.toLowerCase();
            int lowerStart = lowerResponse.indexOf("<projectplans>");
            if (lowerStart != -1) {
                xmlStart = lowerStart;
            } else {
                log.warn("⚠️ XML тег <ProjectPlans> не найден в ответе OpenAI. Возвращаем исходный ответ.");
                return response;
            }
        }
        
        // Ищем конец XML тега </ProjectPlans>
        int xmlEnd = response.indexOf("</ProjectPlans>", xmlStart);
        if (xmlEnd == -1) {
            // Если не нашли, пробуем найти без учета регистра
            String lowerResponse = response.toLowerCase();
            int lowerEnd = lowerResponse.indexOf("</projectplans>", xmlStart);
            if (lowerEnd != -1) {
                xmlEnd = lowerEnd;
            } else {
                log.warn("⚠️ XML тег </ProjectPlans> не найден в ответе OpenAI. Возвращаем часть от начала.");
                return response.substring(xmlStart);
            }
        }
        
        // Извлекаем XML и добавляем закрывающий тег
        String extractedXml = response.substring(xmlStart, xmlEnd + "</ProjectPlans>".length());
        
        log.info("✅ XML успешно извлечен из ответа OpenAI (длина: {} символов)", extractedXml.length());
        
        return extractedXml.trim();
    }
}

