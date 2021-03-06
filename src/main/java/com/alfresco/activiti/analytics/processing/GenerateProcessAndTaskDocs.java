package com.alfresco.activiti.analytics.processing;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.alfresco.activiti.analytics.CustomAnalyticsEndpoint;
import com.alfresco.activiti.analytics.conf.MappingConfiguration;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.ProcessDefinition;
import org.h2.jdbc.JdbcClob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;

@Component("generateProcessAndTaskDocs")
public class GenerateProcessAndTaskDocs {

	protected static final Logger logger = LoggerFactory.getLogger(GenerateProcessAndTaskDocs.class);

	@Autowired
	private HistoryService historyService;

	@Autowired
	private RepositoryService repositoryService;

	@Autowired
	private MappingConfiguration mappingConfiguration;

	@Autowired
	CustomAnalyticsEndpoint customAnalyticsEndpoint;

	@Autowired
	AnalyticsMappingHelper analyticsMappingHelper;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DataSource activitiDataSource;

	@Value("${analytics.sql.eventDataQuery}")
	private String eventDataQuery;

	public void execute(Map<String, Object> processInstance) throws Exception {

		logger.debug(processInstance.toString());

		Map<String, Object> processInstanceData = createProcessMetaData(processInstance);

		// Fetch & Set Process Event Data
		List<Map<String, Object>> processEvents = fetchProcessInstanceEventData(processInstanceData);

		// Create Process Doc
		Map<String, Object> processMap = createAndPublishProcessDocument(processInstanceData, processEvents);

		// Create Task List
		List<HistoricTaskInstance> taskInstances = historyService.createHistoricTaskInstanceQuery()
				.processInstanceId(processInstance.get("processInstanceId").toString()).list();

		// Create Task Docs
		if (taskInstances.size() > 0) {
			for (HistoricTaskInstance taskInstance : taskInstances) {
				createAndPublishTaskDocument(processMap, processEvents, taskInstance);
			}
		}

	}

	private Map<String, Object> createProcessMetaData(Map<String, Object> processInstance) {

		Map<String, Object> payload = new HashMap<String, Object>();
		String processDefinitionId = (String) processInstance.get("processDefinitionId");
		payload.put("procInstanceId", processInstance.get("processInstanceId"));
		payload.put("procDefinitionId", processDefinitionId);
		Map<String, Object> processMetaData = new HashMap<String, Object>();
		ProcessDefinition pd = repositoryService.createProcessDefinitionQuery().processDefinitionId(processDefinitionId)
				.singleResult();
		processMetaData.put("PROCESSDEFINITIONID", processDefinitionId);
		processMetaData.put("PROCESSNAME", pd.getName());
		processMetaData.put("PROCESSVERSION", pd.getVersion());
		processMetaData.put("DEPLOYMENTID", pd.getDeploymentId());
		Deployment dep = repositoryService.createDeploymentQuery().deploymentId(pd.getDeploymentId()).singleResult();
		processMetaData.put("APPNAME", dep.getName());
		payload.put("processMetaData", processMetaData);
		logger.debug("createProcessMetaData() return payload is: " + payload);
		return payload;
	}

	public List<Map<String, Object>> fetchProcessInstanceEventData(Map<String, Object> processInstanceData)
			throws Exception {

		Connection connection = activitiDataSource.getConnection();

		String sql = eventDataQuery + " WHERE PROC_INST_ID_ = '" + processInstanceData.get("processInstanceId")
				+ "' AND TYPE_ IN ('PROCESSINSTANCE_START', 'PROCESSINSTANCE_END', 'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_COMPLETED')";
		logger.debug("fetchProcessInstanceEventData() SQL: " + sql);
		JdbcTemplate jdbcTemplate = new JdbcTemplate(activitiDataSource);
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
		connection.close();
		return handleClob(rows);
	}

	public List<Map<String, Object>> handleClob(List<Map<String, Object>> rows) throws Exception {

		List<Map<String, Object>> newList = new ArrayList<Map<String, Object>>();

		for (Map<String, Object> map : rows) {
			Map<String, Object> transformedMap = new HashMap<String, Object>();

			for (String name : map.keySet()) {

				Object value = map.get(name);

				if (value instanceof JdbcClob) {

					String clobString = org.apache.commons.io.IOUtils.toString(((JdbcClob) value).getCharacterStream());
					// value =
					// org.apache.commons.lang.StringEscapeUtils.unescapeJava(clobString);
					value = clobString;

				}
				transformedMap.put(name, value);
			}
			newList.add(transformedMap);
		}
		return newList;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> createAndPublishProcessDocument(Map<String, Object> processData,
			List<Map<String, Object>> processEvents) throws JsonParseException, JsonMappingException, IOException {

		/*
		 * Error handling at a run level is yet to be done. At the moment the
		 * error handling is just a write to log!
		 */

		List<Map<String, Object>> endStateConfigJSON = mappingConfiguration.getEndStateConfigMap();
		Map<String, Object> configJSON = mappingConfiguration.getAllProcessConfigMap();

		// @SuppressWarnings("unchecked")
		// Map<String, Object> processReportMetadata = (Map<String, Object>)
		// processData.get("processReportMetadata");
		String procDefinitionId = (String) processData.get("procDefinitionId");
		String procInstanceId = (String) processData.get("procInstanceId");

		Map<String, Object> processMetaData = (Map<String, Object>) processData.get("processMetaData");

		HistoricProcessInstance processInstanceDetails = historyService.createHistoricProcessInstanceQuery()
				.includeProcessVariables().processInstanceId(procInstanceId).singleResult();

		String processState = processInstanceDetails != null
				? (processInstanceDetails.getEndTime() != null ? "Complete" : "Running") : "Unknown";

		Map<String, Object> processMap = new HashMap<String, Object>();

		try {
			logger.debug("process data mapping start: " + procInstanceId);

			processMap.put("EventType", "ProcessInstance");
			processMap.put("ProcessState", processState);
			Map<String, Object> processVariables = new HashMap<String, Object>();
			Map<String, Object> processStartVariables = new HashMap<String, Object>();

			if (!processState.equals("Unknown")) {
				logger.debug("process data mapping start: " + procInstanceId);

				for (Map<String, Object> processEvent : processEvents) {
					logger.debug("process event loop" + processEvent.toString());
					if (processEvent.get("TYPE_").equals("PROCESSINSTANCE_START")) {

						Map<String, Object> startEvent = processEvent;
						if (startEvent.get("DATA_") != null) {

							Map<String, Object> vars = (Map<String, Object>) (new ObjectMapper()
									.readValue(startEvent.get("DATA_").toString(), Map.class).get("variables"));
							if (vars != null) {
								for (Map.Entry<String, Object> entry : vars.entrySet()) {
									processStartVariables.put(entry.getKey(), entry.getValue());
								}
							}
							processMap.put("processStartVariables", processStartVariables);
							logger.debug("mapped process start variables: " + procInstanceId);
						}
						break;
					}
				}

				logger.debug("before common process variable mapping: " + procInstanceId);

				for (Map<String, Object> mapping : (List<Map<String, Object>>) configJSON.get("mappings")) {
					String newKey = (String) mapping.get("name");
					/*
					 * Do the mapping only if the value associated with the key
					 * is null. This is to handle a scenario where in one
					 * version of process, it could be variable "a" getting
					 * mapped to new name "c" and in another version it could be
					 * another variable "b" getting mapped to the new variable
					 * "c"
					 */

					if (processMap.get(newKey) == null) {
						// analyticsMappingHelperObject.lookupMapping(execution,
						// userInfoBean, mapping, processInstanceDetails,
						// processMetaData, tableRow, taskDefinitionKeys,
						// endStateConfigJSON, taskInstanceDetails,
						// taskCompleteEventJSON)
						Map<String, Object> lookupResult = analyticsMappingHelper.lookupMapping(mapping,
								processInstanceDetails, processMetaData, null, endStateConfigJSON, null, null);
						processMap.put(newKey, lookupResult.get("value"));
						for (Map.Entry<String, Object> entry : lookupResult.entrySet()) {
							if (!entry.getKey().equals("value")) {
								processMap.put(entry.getKey(), entry.getValue());
							}
						}
					}
				}

				logger.debug("after common process variable mapping: " + procInstanceId);

				Map<String, Object> processSpecificData = new HashMap<String, Object>();
				List<Map<String, Object>> processMappingConfigList = (List<Map<String, Object>>) configJSON
						.get("processes");
				if (processMappingConfigList != null) {
					for (Map<String, Object> processMappingConfig : processMappingConfigList) {
						if (processMappingConfig.get("processName").equals(processMetaData.get("PROCESSNAME"))
								&& processMappingConfig.get("mappingConfigFileName") != null) {
							logger.debug("inside custom variable mapping: " + procInstanceId);

							URL processSpecificMappingFile = Resources
									.getResource("mappingconfig/" + processMappingConfig.get("mappingConfigFileName"));
							String processSpecificMappingJSON = Resources.toString(processSpecificMappingFile,
									Charsets.UTF_8);
							Map<String, Object> processSpecificMapping = new ObjectMapper()
									.readValue(processSpecificMappingJSON, new TypeReference<Map<String, Object>>() {
									});

							for (Map<String, Object> mapping : (List<Map<String, Object>>) processSpecificMapping
									.get("mappings")) {
								String newKey = (String) mapping.get("name");

								if (processSpecificData.get(newKey) == null) {
									Map<String, Object> lookupResult = analyticsMappingHelper.lookupMapping(mapping,
											processInstanceDetails, processMetaData, null, endStateConfigJSON, null,
											null);
									processSpecificData.put(newKey, lookupResult.get("value"));
									for (Map.Entry<String, Object> entry : lookupResult.entrySet()) {
										if (!entry.getKey().equals("value")) {
											processSpecificData.put(entry.getKey(), entry.getValue());
										}
									}
								}
							}
							processMap.put("processSpecificCustomMap", processSpecificData);
						}
					}
				}

				logger.debug("after custom variable mapping: " + procInstanceId);

				processVariables.putAll(processInstanceDetails.getProcessVariables());

				logger.debug("after variable mapping: " + procInstanceId);
				processMap.put("variables", processVariables);
				logger.debug("process data mapping end(if): " + procInstanceId);

			} // end if _obj
			else {
				logger.warn("Empty payload received for process with id " + procInstanceId);
				// Sometimes we can have events in the table and the api not
				// return data. In this case create a doc with just the id &
				// processDefinitionId.
				processMap.put("ProcessDefinitionId", procDefinitionId);
				processMap.put("ProcessInstanceId", procInstanceId);
			}

			customAnalyticsEndpoint.publishProcessDocument(processMap, processInstanceDetails);
			return processMap;
		} catch (Exception e) {
			// for any errors log and continue
			logger.error("Error processing process with id " + procInstanceId + e.toString());
			return processMap;
		}

	}

	@SuppressWarnings("unchecked")
	public void createAndPublishTaskDocument(Map<String, Object> processMap, List<Map<String, Object>> processEvents,
			HistoricTaskInstance taskInstanceDetails) {

		/*
		 * Error handling at a run level is yet to be done. At the moment the
		 * error handling is just a write to log!
		 */

		Map<String, Object> configJSON = mappingConfiguration.getAllTasksConfigMap();

		Map<String, Object> taskMap = new HashMap<String, Object>();
		taskMap.putAll(processMap);
		String taskId = taskInstanceDetails.getId();
		String procInstanceId = taskInstanceDetails.getProcessInstanceId();

		try {
			logger.debug("task data mapping start: " + taskId);

			taskMap.put("EventType", "TaskInstance");
			taskMap.put("TaskState", taskInstanceDetails.getEndTime() != null ? "Complete" : "Active");

			Map<String, Object> taskCompleteVariables = new HashMap<String, Object>();
			Map<String, Object> taskCompleteEventData = new HashMap<String, Object>();

			for (Map<String, Object> processEvent : processEvents) {
				if (processEvent.get("TYPE_").equals("TASK_COMPLETED") && processEvent.get("TASK_ID_").equals(taskId)) {
					Map<String, Object> taskCompleteEvent = processEvent;
					if (taskCompleteEvent.get("DATA_") != null) {
						taskCompleteEventData = new ObjectMapper().readValue(taskCompleteEvent.get("DATA_").toString(),
								Map.class);

						Map<String, Object> vars = (Map<String, Object>) taskCompleteEventData.get("variables");
						if (vars != null) {
							for (Map.Entry<String, Object> entry : vars.entrySet()) {
								taskCompleteVariables.put(entry.getKey(), entry.getValue());
							}
						}
						taskMap.put("taskCompleteVariables", taskCompleteVariables);
						logger.debug("after task complete variable mapping: " + procInstanceId + "; task: " + taskId);
					}
					break;
				}
			}

			logger.debug("before task level common mapping: " + procInstanceId + "; task: " + taskId);

			for (Map<String, Object> mapping : (List<Map<String, Object>>) configJSON.get("mappings")) {
				String newKey = (String) mapping.get("name");
				/*
				 * Do the mapping only if the value associated with the key is
				 * null. This is to handle a scenario where in one version of
				 * process, it could be variable "a" getting mapped to new name
				 * "c" and in another version it could be another variable "b"
				 * getting mapped to the new variable "c"
				 */

				if (taskMap.get(newKey) == null) {
					Map<String, Object> lookupResult = analyticsMappingHelper.lookupMapping(mapping, null, null, null,
							null, taskInstanceDetails, taskCompleteEventData);
					taskMap.put(newKey, lookupResult.get("value"));
					for (Map.Entry<String, Object> entry : lookupResult.entrySet()) {
						if (!entry.getKey().equals("value")) {
							taskMap.put(entry.getKey(), entry.getValue());
						}
					}
				}
			}
			logger.debug("after task level common mapping: " + procInstanceId + "; task: " + taskId);

			customAnalyticsEndpoint.publishTaskDocument(taskMap, taskInstanceDetails);

		} catch (Exception e) {
			// for any errors log and continue
			logger.error("Error processing processId: " + procInstanceId + "; taskId: " + taskId + " " + e.toString());
		}

	}

}
