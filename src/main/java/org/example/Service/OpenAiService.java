package org.example.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.SslProvider;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.time.Duration;

import java.io.FileWriter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Service for interacting with Azure OpenAI API to generate project plans.
 * 
 * <p>This service generates project plans in XML format using Azure OpenAI.
 * It creates separate prompts for PRINCE2 and Scrum methodologies and combines
 * the results into a single XML document. All requests and responses are logged
 * to files for debugging purposes.</p>
 * 
 * @author AI Project Planner Team
 * @version 1.0
 */
@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    
    // Устанавливаем таймаут SSL handshake ДО инициализации Netty
    static {
        System.setProperty("io.netty.handler.ssl.SslHandler.handshakeTimeoutMillis", "60000");
        log.info("SSL handshake timeout set to 60000ms via system property");
    }
    
    private final WebClient webClient;
    private final String deploymentName;
    private static final String LOG_DIRECTORY = "logs";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Constructs a new OpenAiService with the provided configuration.
     * 
     * @param apiKey the Azure OpenAI API key
     * @param endpoint the Azure OpenAI endpoint URL
     * @param deploymentName the deployment name for the OpenAI model
     */
    public OpenAiService(
        @Value("${azure.openai.api-key}") String apiKey,
        @Value("${azure.openai.endpoint}") String endpoint,
        @Value("${azure.openai.deployment-name}") String deploymentName
    ) {
        // Настройка HttpClient с увеличенными таймаутами для SSL handshake и подключения
        WebClient.Builder webClientBuilder = WebClient.builder()
            .baseUrl(endpoint)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        
        try {
            // Настройка SslProvider с увеличенным таймаутом handshake
            SslProvider sslProvider = SslProvider.builder()
                .sslContext(SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build())
                .handshakeTimeout(Duration.ofSeconds(60))
                .closeNotifyFlushTimeout(Duration.ofSeconds(10))
                .closeNotifyReadTimeout(Duration.ofSeconds(10))
            .build();
            
            HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(180))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000)
                .secure(sslProvider)
                .doOnConnected(conn -> {
                    conn.addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(180))
                        .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(180));
                });
            
            webClientBuilder.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));
            log.info("HttpClient configured with extended timeouts (SSL: 60s, Connect: 60s, Response: 180s)");
        } catch (Exception e) {
            log.error("Failed to configure HttpClient with extended timeouts, using default: {}", e.getMessage());
        }
        
        this.webClient = webClientBuilder.build();
        this.deploymentName = deploymentName;
        
        log.info("OpenAiService initialized with endpoint: {} (with extended timeouts)", endpoint);
    }

    /**
     * Gets a recommendation for which methodology (Scrum or PRINCE2) to use for the project.
     * Returns both the recommendation and detailed reasoning.
     * 
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @return a map containing "recommendation" ("scrum" or "prince2") and "reasoning" (detailed analysis)
     */
    public Map<String, String> getMethodologyRecommendation(String projectName, String projectDescription) {
        try {
            String prompt = String.format("""
                    Analyzuj nasledujúci projekt a odporuč, či je vhodnejší Scrum alebo PRINCE2.
                    
                    Názov projektu: %s
                    Popis projektu: %s
                    
                    Úloha:
                    1. Detailne analyzuj charakteristiky tohto projektu
                    2. Porovnaj ho s kritériami pre Scrum a PRINCE2
                    3. Odporuč jednu metodológiu s konkrétnym odôvodnením
                    
                    Kritériá pre Scrum:
                    - Iteratívny vývoj produktov
                    - Projekty s meniacimi sa požiadavkami
                    - Agilné tímy
                    - Produkty, ktoré sa môžu postupne zdokonaľovať
                    - Rýchle dodávky funkčných častí
                    - Flexibilita a adaptabilita
                    
                    Kritériá pre PRINCE2:
                    - Projekty s jasne definovanými fázami
                    - Projekty s pevnými termínmi a rozpočtom
                    - Projekty vyžadujúce formálnu dokumentáciu
                    - Projekty s jasnými deliverablemi
                    - Projekty s vysokou mierou kontroly a riadenia
                    - Projekty vyžadujúce schvaľovacie procesy
                    
                    Vráť odpoveď v nasledujúcom formáte (iba text, bez XML, bez Markdown):
                    
                    ODporúčanie: [scrum alebo prince2]
                    
                    Odôvodnenie:
                    [Detailná analýza projektu (2-4 vety). Vysvetli, prečo je vybraná metodológia vhodnejšia pre tento konkrétny projekt. Uveď konkrétne charakteristiky projektu, ktoré podporujú túto voľbu.]
                    
                    Kľúčové faktory:
                    - [Faktor 1: konkrétny dôvod prečo Scrum/PRINCE2]
                    - [Faktor 2: ďalší dôvod]
                    - [Faktor 3: ďalší dôvod]
                    """, projectName, projectDescription);
            
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            String logFile = String.format("request_%s_recommendation.log", timestamp);
            String response = callOpenAi(prompt, logFile);
            
            // Parse response
            String recommendation = "scrum"; // default
            String reasoning = response;
            
            // Extract recommendation
            String lowerResponse = response.toLowerCase();
            if (lowerResponse.contains("odporúčanie:") || lowerResponse.contains("odporučanie:")) {
                if (lowerResponse.contains("prince2") || lowerResponse.contains("prince")) {
                    recommendation = "prince2";
                } else if (lowerResponse.contains("scrum")) {
                    recommendation = "scrum";
                }
            } else {
                // Fallback: check for methodology mentions
                if (lowerResponse.contains("prince2") || lowerResponse.contains("prince")) {
                    recommendation = "prince2";
                }
            }
            
            // Clean up reasoning text
            reasoning = response.trim();
            // Remove "ODporúčanie:" line if present
            reasoning = reasoning.replaceAll("(?i)odporúčanie:\\s*(scrum|prince2|prince).*?\\n", "");
            reasoning = reasoning.replaceAll("(?i)odporučanie:\\s*(scrum|prince2|prince).*?\\n", "");
            
            return Map.of("recommendation", recommendation, "reasoning", reasoning);
        } catch (Exception e) {
            log.error("Chyba pri získavaní odporúčania, používam predvolené (scrum): {}", e.getMessage());
            // Return default recommendation on error
            return Map.of("recommendation", "scrum", 
                         "reasoning", "Nepodarilo sa získať detailné odporúčanie. Predvolená metodológia: Scrum (vhodná pre väčšinu agilných projektov).");
        }
    }

    /**
     * Generates a complete project plan in XML format using Azure OpenAI with multi-step approach.
     * 
     * <p>This method uses a multi-step generation process:
     * 1. First generates a skeleton with all stages/sprints (basic info)
     * 2. Then details each part in separate requests to avoid token limits</p>
     * 
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @param methodology "scrum" or "prince2"
     * @return a complete XML document for the selected methodology
     * @throws RuntimeException if the API call fails or the response is invalid
     */
    public String generateProjectPlan(String projectName, String projectDescription, String methodology) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        log.info("Generujem {} plán pre projekt: {}", methodology, projectName);

        // Step 1: Generate skeleton
        String skeletonXml = generateSkeleton(projectName, projectDescription, methodology, timestamp);
        
        // Step 2: Detail each part
        String detailedXml = detailProjectPlan(skeletonXml, projectName, projectDescription, methodology, timestamp);
        
        logToFile(String.format("request_%s_%s_final.log", timestamp, methodology), "=== FINAL_XML ===", detailedXml);
        
        return detailedXml;
    }

    /**
     * Legacy method for backward compatibility - generates both methodologies.
     */
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

    /**
     * Generates a skeleton XML structure with all stages/sprints but minimal details.
     * 
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @param methodology "scrum" or "prince2"
     * @param timestamp timestamp for logging
     * @return skeleton XML with basic structure
     */
    private String generateSkeleton(String projectName, String projectDescription, String methodology, String timestamp) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        String prompt;
        if ("prince2".equalsIgnoreCase(methodology)) {
            prompt = String.format("""
                Si certifikovaný PRINCE2 projektový manažér. Vytvor SKELEL (kostru) projektu - iba základnú štruktúru bez detailov.
                Vráť iba XML dokument s koreňom <PRINCE2Project>. Žiadny text mimo XML.
                
                Názov projektu: %s
                Popis projektu: %s
                
                Vytvor SKELEL s:
                - 4-5 Stage (iba názvy, krátke popisy, dátumy, priority)
                - Každý Stage má iba 1-2 Task (iba názvy, bez detailov)
                - Bez Outputs, Risks, Deliverables (pridáme neskôr)
                
                Formát:
                <PRINCE2Project>
                  <Name>%s</Name>
                  <Description>%s</Description>
                  <Stages>
                    <Stage>
                      <StageID>1</StageID>
                      <Name>Názov fázy</Name>
                      <Description>Krátky popis</Description>
                      <DueDate>%s</DueDate>
                      <Priority>High</Priority>
                      <Tasks>
                        <Task>
                          <Title>Názov úlohy</Title>
                          <Description>Krátky popis</Description>
                        </Task>
                      </Tasks>
                    </Stage>
                    <!-- ďalšie Stage -->
                  </Stages>
                </PRINCE2Project>
                """, projectName, projectDescription, projectName, projectDescription, today);
        } else {
            prompt = String.format("""
                Si skúsený Scrum master. Vytvor SKELEL (kostru) projektu - iba základnú štruktúru bez detailov.
                Vráť iba XML dokument s koreňom <ScrumProject>. Žiadny text mimo XML.
                
                Názov projektu: %s
                Popis projektu: %s
                
                Vytvor SKELEL s:
                - 3-4 Sprint (iba názvy, ciele, dátumy)
                - Každý Sprint má 1-2 Epic (iba názvy)
                - Každý Epic má 1-2 Story (iba názvy, bez detailov)
                - Bez AcceptanceCriteria, SubTasks (pridáme neskôr)
                
                Formát:
                <ScrumProject>
                  <Name>%s</Name>
                  <Description>%s</Description>
                  <Sprints>
                    <Sprint>
                      <SprintID>1</SprintID>
                      <Name>Názov šprintu</Name>
                      <Goal>Cieľ šprintu</Goal>
                      <DueDate>%s</DueDate>
                      <Epics>
                        <Epic>
                          <EpicID>E1</EpicID>
                          <Title>Názov epiku</Title>
                          <Stories>
                            <Story>
                              <ID>S1</ID>
                              <Title>Názov story</Title>
                              <Description>Krátky popis</Description>
                            </Story>
                          </Stories>
                        </Epic>
                      </Epics>
                    </Sprint>
                    <!-- ďalšie Sprint -->
                  </Sprints>
                </ScrumProject>
                """, projectName, projectDescription, projectName, projectDescription, today);
        }
        
        String logFile = String.format("request_%s_%s_skeleton.log", timestamp, methodology);
        String response = callOpenAi(prompt, logFile);
        
        if ("prince2".equalsIgnoreCase(methodology)) {
            return extractXmlSection(response, "PRINCE2Project");
        } else {
            return extractXmlSection(response, "ScrumProject");
        }
    }

    /**
     * Details the skeleton by adding full information to each part in separate requests.
     * 
     * @param skeletonXml the skeleton XML structure
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @param methodology "scrum" or "prince2"
     * @param timestamp timestamp for logging
     * @return fully detailed XML
     */
    private String detailProjectPlan(String skeletonXml, String projectName, String projectDescription, 
                                     String methodology, String timestamp) {
        if ("prince2".equalsIgnoreCase(methodology)) {
            return detailPrince2Plan(skeletonXml, projectName, projectDescription, timestamp);
        } else {
            return detailScrumPlan(skeletonXml, projectName, projectDescription, timestamp);
        }
    }

    /**
     * Details a PRINCE2 plan by processing stages in batches.
     */
    private String detailPrince2Plan(String skeletonXml, String projectName, String projectDescription, String timestamp) {
        try {
            // Parse skeleton to get stages
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(skeletonXml.getBytes(StandardCharsets.UTF_8)));
            
            NodeList stages = doc.getElementsByTagName("Stage");
            int stageCount = stages.getLength();
            
            log.info("Detailing {} PRINCE2 stages", stageCount);
            
            // Process all stages in one request if <= 4, otherwise process 3 at a time
            // This maximizes token usage while staying within ~2000 token limit
            StringBuilder detailedXml = new StringBuilder();
            detailedXml.append("<PRINCE2Project>\n");
            detailedXml.append("  <Name>").append(projectName).append("</Name>\n");
            detailedXml.append("  <Description>").append(projectDescription).append("</Description>\n");
            detailedXml.append("  <Stages>\n");
            
            int batchSize = stageCount <= 4 ? stageCount : 3; // Process all if <=4, otherwise 3 at a time
            
            for (int i = 0; i < stageCount; i += batchSize) {
                int endIndex = Math.min(i + batchSize, stageCount);
                String stageRange = String.format("stages %d-%d", i + 1, endIndex);
                
                log.info("Detailing PRINCE2 {} (batch size: {})", stageRange, batchSize);
                
                String prompt = buildDetailPrince2Prompt(skeletonXml, projectName, projectDescription, i, endIndex, stageCount);
                String logFile = String.format("request_%s_prince2_detail_%d.log", timestamp, i / batchSize + 1);
                String response = callOpenAi(prompt, logFile);
                
                // Extract detailed stages from response
                String detailedStages = extractStagesFromResponse(response, "PRINCE2Project");
                detailedXml.append(detailedStages);
            }
            
            detailedXml.append("  </Stages>\n");
            detailedXml.append("</PRINCE2Project>");
            
            return detailedXml.toString();
        } catch (Exception e) {
            log.error("Error detailing PRINCE2 plan, returning skeleton: {}", e.getMessage());
            return skeletonXml;
        }
    }

    /**
     * Details a Scrum plan by processing sprints in batches.
     */
    private String detailScrumPlan(String skeletonXml, String projectName, String projectDescription, String timestamp) {
        try {
            // Parse skeleton to get sprints
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(skeletonXml.getBytes(StandardCharsets.UTF_8)));
            
            NodeList sprints = doc.getElementsByTagName("Sprint");
            int sprintCount = sprints.getLength();
            
            log.info("Detailing {} Scrum sprints", sprintCount);
            
            // Process 2 sprints at a time to maximize token usage (sprints are complex but we can fit 2 in ~2000 tokens)
            // If only 1-2 sprints, process all at once
            StringBuilder detailedXml = new StringBuilder();
            detailedXml.append("<ScrumProject>\n");
            detailedXml.append("  <Name>").append(projectName).append("</Name>\n");
            detailedXml.append("  <Description>").append(projectDescription).append("</Description>\n");
            detailedXml.append("  <Sprints>\n");
            
            int batchSize = sprintCount <= 2 ? sprintCount : 2; // Process all if <=2, otherwise 2 at a time
            
            for (int i = 0; i < sprintCount; i += batchSize) {
                int endIndex = Math.min(i + batchSize, sprintCount);
                String sprintRange = String.format("sprints %d-%d", i + 1, endIndex);
                
                log.info("Detailing Scrum {} (batch size: {})", sprintRange, batchSize);
                
                String prompt = buildDetailScrumPrompt(skeletonXml, projectName, projectDescription, i, endIndex, sprintCount);
                String logFile = String.format("request_%s_scrum_detail_%d.log", timestamp, i / batchSize + 1);
                String response = callOpenAi(prompt, logFile);
                
                // Extract detailed sprints from response
                String detailedSprints = extractSprintsFromResponse(response, "ScrumProject");
                detailedXml.append(detailedSprints);
            }
            
            detailedXml.append("  </Sprints>\n");
            detailedXml.append("</ScrumProject>");
            
            return detailedXml.toString();
        } catch (Exception e) {
            log.error("Error detailing Scrum plan, returning skeleton: {}", e.getMessage());
            return skeletonXml;
        }
    }

    /**
     * Builds prompt for detailing PRINCE2 stages.
     * Optimized to be shorter and more efficient with tokens.
     */
    private String buildDetailPrince2Prompt(String skeletonXml, String projectName, String projectDescription, 
                                           int startIndex, int endIndex, int totalStages) {
        // Use shorter skeleton - only relevant stages
        String relevantSkeleton = extractRelevantStagesFromSkeleton(skeletonXml, startIndex, endIndex);
        
        return String.format("""
            PRINCE2 manažér. Detailizuj Stage %d-%d z kostry. Iba XML, žiadny text.
            
            Projekt: %s - %s
            
            Kostra (iba relevantné Stage):
            %s
            
            Úloha: Pre každý Stage pridaj:
            - Plný Description (2-3 vety)
            - Outputs: 2-3 konkrétne výstupy
            - Risks: 2-3 riziká s mitigáciou (1 veta na riziko)
            - Pre Stage: Priority, DueDate, Assignee, Labels
            - Pre každý Task: plný Description (1-2 vety), Priority, DueDate, Assignee, Labels
            
            Formát XML (iba Stage %d-%d):
            <Stage><StageID>X</StageID><Name>...</Name><Description>Plný popis</Description><DueDate>YYYY-MM-DD</DueDate><Priority>High|Medium|Low</Priority><Assignee>meno@example.com</Assignee><Labels>tag1,tag2</Labels><Outputs><Output>Výstup 1</Output><Output>Výstup 2</Output></Outputs><Risks><Risk>Riziko s mitigáciou</Risk></Risks><Tasks><Task><Title>...</Title><Description>Plný popis</Description><Priority>High|Medium|Low</Priority><DueDate>YYYY-MM-DD</DueDate><Assignee>meno@example.com</Assignee><Labels>tag1,tag2</Labels></Task></Tasks></Stage>
            
            Vráť IBA Stage %d-%d, bez wrapper tagov.
            """, startIndex + 1, endIndex, projectName, projectDescription, relevantSkeleton, startIndex + 1, endIndex, startIndex + 1, endIndex);
    }
    
    /**
     * Extracts only relevant stages from skeleton to reduce prompt size.
     */
    private String extractRelevantStagesFromSkeleton(String skeletonXml, int startIndex, int endIndex) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(skeletonXml.getBytes(StandardCharsets.UTF_8)));
            
            NodeList stages = doc.getElementsByTagName("Stage");
            StringBuilder relevant = new StringBuilder();
            
            for (int i = startIndex; i < Math.min(endIndex, stages.getLength()); i++) {
                org.w3c.dom.Node stage = stages.item(i);
                relevant.append(nodeToString(stage));
            }
            
            return relevant.toString();
        } catch (Exception e) {
            log.warn("Could not extract relevant stages, using full skeleton: {}", e.getMessage());
            return skeletonXml;
        }
    }
    
    /**
     * Converts a DOM node to string representation.
     */
    private String nodeToString(org.w3c.dom.Node node) {
        try {
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
            java.io.StringWriter writer = new java.io.StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(node), new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds prompt for detailing Scrum sprints.
     */
    private String buildDetailScrumPrompt(String skeletonXml, String projectName, String projectDescription, 
                                         int startIndex, int endIndex, int totalSprints) {
        return String.format("""
            Si skúsený Scrum master. Detailizuj konkrétne Sprint z kostry projektu.
            Vráť iba XML fragment s detailizovanými Sprint. Žiadny text mimo XML.
            
            Kontext projektu:
            - Názov: %s
            - Popis: %s
            
            Kostra projektu (celá štruktúra):
            %s
            
            Úloha: Detailizuj Sprint č. %d až %d (z celkovo %d).
            
            Pre Sprint pridaj:
            - Plné Description
            - Risks (2-3 riziká)
            - Pre každý Epic: plný Description, Priority, Assignee, Labels
            - Pre každú Story: plný Description, StoryPoints, Priority, DueDate, Assignee, Labels
            - AcceptanceCriteria (3-4 kritériá pre každú Story)
            - SubTasks (2-3 sub-tasky pre každú Story)
            
            Vráť iba XML fragment:
            <Sprint>
              <SprintID>%d</SprintID>
              <Name>...</Name>
              <Goal>...</Goal>
              <Description>Plný popis šprintu</Description>
              <DueDate>YYYY-MM-DD</DueDate>
              <Priority>High|Medium|Low</Priority>
              <Assignee>meno@example.com</Assignee>
              <Labels>tag1,tag2</Labels>
              <Risks>
                <Risk>Riziko 1</Risk>
                <Risk>Riziko 2</Risk>
              </Risks>
              <Epics>
                <Epic>
                  <EpicID>E1</EpicID>
                  <Title>...</Title>
                  <Description>Plný popis epiku</Description>
                  <Priority>High|Medium|Low</Priority>
                  <Assignee>meno@example.com</Assignee>
                  <Labels>tag1,tag2</Labels>
                  <Stories>
                    <Story>
                      <ID>S1</ID>
                      <Title>...</Title>
                      <Description>Plný popis story</Description>
                      <StoryPoints>3</StoryPoints>
                      <Priority>High|Medium|Low</Priority>
                      <DueDate>YYYY-MM-DD</DueDate>
                      <Assignee>meno@example.com</Assignee>
                      <Labels>tag1,tag2</Labels>
                      <AcceptanceCriteria>
                        <Criterion>Kritérium 1</Criterion>
                        <Criterion>Kritérium 2</Criterion>
                      </AcceptanceCriteria>
                      <SubTasks>
                        <Task>
                          <Title>Sub-task 1</Title>
                          <Description>Popis sub-tasku</Description>
                          <Priority>Medium</Priority>
                          <Assignee>meno@example.com</Assignee>
                        </Task>
                      </SubTasks>
                    </Story>
                  </Stories>
                </Epic>
              </Epics>
            </Sprint>
            
            Dôležité: Vráť IBA Sprint v rozsahu %d-%d, bez wrapper tagov.
            """, projectName, projectDescription, skeletonXml, startIndex + 1, endIndex, totalSprints, startIndex + 1, endIndex);
    }

    /**
     * Extracts stages from a detailed response.
     */
    private String extractStagesFromResponse(String response, String rootTag) {
        try {
            // Remove markdown code blocks if present
            String cleanResponse = response;
            if (cleanResponse.contains("```xml")) {
                int start = cleanResponse.indexOf("```xml");
                int end = cleanResponse.lastIndexOf("```");
                if (start != -1 && end != -1 && end > start) {
                    cleanResponse = cleanResponse.substring(start + 6, end).trim();
                }
            } else if (cleanResponse.contains("```")) {
                int start = cleanResponse.indexOf("```");
                int end = cleanResponse.lastIndexOf("```");
                if (start != -1 && end != -1 && end > start) {
                    cleanResponse = cleanResponse.substring(start + 3, end).trim();
                }
            }
            
            // Find <Stage> tags - use non-greedy matching
            Pattern pattern = Pattern.compile("(<Stage>.*?</Stage>)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(cleanResponse);
            StringBuilder stages = new StringBuilder();
            while (matcher.find()) {
                stages.append("    ").append(matcher.group(1)).append("\n");
            }
            
            if (stages.length() == 0) {
                log.warn("No Stage tags found in response. Response preview: {}", cleanResponse.substring(0, Math.min(200, cleanResponse.length())));
            }
            
            return stages.toString();
        } catch (Exception e) {
            log.error("Error extracting stages: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Extracts sprints from a detailed response (can be multiple).
     */
    private String extractSprintsFromResponse(String response, String rootTag) {
        try {
            // Remove markdown code blocks if present
            String cleanResponse = response;
            if (cleanResponse.contains("```xml")) {
                int start = cleanResponse.indexOf("```xml");
                int end = cleanResponse.lastIndexOf("```");
                if (start != -1 && end != -1 && end > start) {
                    cleanResponse = cleanResponse.substring(start + 6, end).trim();
                }
            } else if (cleanResponse.contains("```")) {
                int start = cleanResponse.indexOf("```");
                int end = cleanResponse.lastIndexOf("```");
                if (start != -1 && end != -1 && end > start) {
                    cleanResponse = cleanResponse.substring(start + 3, end).trim();
                }
            }
            
            // Find all <Sprint> tags - use non-greedy matching
            Pattern pattern = Pattern.compile("(<Sprint>.*?</Sprint>)", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(cleanResponse);
            StringBuilder sprints = new StringBuilder();
            while (matcher.find()) {
                sprints.append("    ").append(matcher.group(1)).append("\n");
            }
            
            if (sprints.length() == 0) {
                log.warn("No Sprint tags found in response. Response preview: {}", cleanResponse.substring(0, Math.min(200, cleanResponse.length())));
            }
            
            return sprints.toString();
        } catch (Exception e) {
            log.error("Error extracting sprints: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Builds the prompt for generating a PRINCE2 project plan.
     * 
     * <p>The prompt instructs the AI to generate a structured XML document
     * following the PRINCE2 methodology with stages, tasks, outputs, and risks.</p>
     * 
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @return the formatted prompt string for PRINCE2 generation
     */
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

    /**
     * Builds the prompt for generating a Scrum project plan.
     * 
     * <p>The prompt instructs the AI to generate a structured XML document
     * following the Scrum methodology with sprints, epics, stories, and tasks.</p>
     * 
     * @param projectName the name of the project
     * @param projectDescription the description of the project
     * @return the formatted prompt string for Scrum generation
     */
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

    /**
     * Makes an API call to Azure OpenAI with the given prompt.
     * 
     * <p>The request and response are logged to a file for debugging.
     * Uses max_tokens=8000 to ensure complete responses.</p>
     * 
     * @param prompt the prompt to send to OpenAI
     * @param logFileName the name of the log file to write request/response to
     * @return the content of the AI response
     * @throws RuntimeException if the API call fails or the response is invalid
     */
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

    /**
     * Extracts an XML section from the content using the specified root tag.
     * 
     * <p>Uses regex pattern matching to find the XML section between opening
     * and closing tags. The search is case-insensitive and handles multiline content.</p>
     * 
     * @param content the content to search in
     * @param rootTag the root XML tag to extract (e.g., "PRINCE2Project", "ScrumProject")
     * @return the extracted XML section
     * @throws RuntimeException if the root tag is not found in the content
     */
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

    /**
     * Logs content to a file in the logs directory.
     * 
     * @param fileName the name of the log file
     * @param section the section header (e.g., "=== REQUEST ===")
     * @param content the content to log
     */
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

    /**
     * Logs metadata from the OpenAI API response to the log file.
     * 
     * <p>Extracts and logs token usage, model information, and request ID
     * from the API response for debugging and monitoring purposes.</p>
     * 
     * @param fileName the name of the log file
     * @param response the API response map containing metadata
     */
    @SuppressWarnings("unchecked")
    private void logMetadata(String fileName, Map<String, Object> response) {
        try {
            StringBuilder metadata = new StringBuilder();
            metadata.append("\n=== METADATA ===\n");
            
            if (response.containsKey("usage")) {
                Map<String, Object> usage = (Map<String, Object>) response.get("usage");
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

}

