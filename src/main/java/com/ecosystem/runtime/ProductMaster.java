/**
 * Product Master is the main class where scoring engine services are invoked from.
 */

package com.ecosystem.runtime;

import com.ecosystem.EcosystemBatch;
import com.ecosystem.EcosystemMaster;
import com.ecosystem.EcosystemResponse;
import com.ecosystem.data.mongodb.ConnectionFactory;
import com.ecosystem.runtime.authentication.KeyManagement;
import com.ecosystem.worker.kafka.BasicProducerKerberos;
import com.ecosystem.utils.ActivityLog;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.utils.JSONDecode;
import com.ecosystem.worker.h2o.ModelMojoWorkerH2O;
import com.ecosystem.worker.h2o.RunModelMojo;
import com.mongodb.client.MongoCollection;
import hex.genmodel.easy.EasyPredictModelWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.annotation.security.RolesAllowed;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import static com.ecosystem.EcosystemMaster.generateUUID;
import static com.ecosystem.data.mongodb.MongoDBWorkerLogging.addLoggingAsync;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

@CrossOrigin
@RolesAllowed({"ADMIN", "USER"})
@RestController()
@SecurityScheme(type = SecuritySchemeType.APIKEY)
@ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successful",
                content = { @Content(mediaType = "application/json",
                        schema = @Schema(implementation = ProductMaster.class)) }),
        @ApiResponse(responseCode = "400", description = "Error",
                content = @Content),
        @ApiResponse(responseCode = "404", description = "Error",
                content = @Content) })
@Tag(name = "Predictors", description = "Review model domain details, refresh model parameter loading and perform predictions. " +
        "There are two primary approaches to invoking a prediction for scoring via a model namely; Invoke model and return a JSON response that can be used in any application," +
        " invoke model and deliver result onto a KAFKA topic of your choosing. Model can also be tested by dynamically loading a MOJO, mostly used for testing purposes." +
        "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters " +
        "from a data source." +
        "\nUse this format for input prams only:\n" +
        "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y']}" +
        "\nUse this approach for inputs from data source:\n" +
        "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
        "\nIf there is post-scoring logic, then ise this configuration:\n" +
        "{'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}}\n")

public class ProductMaster {

    private static final Logger LOGGER = LogManager.getLogger(ProductMaster.class.getName());

    public String ACCESS_KEY = "";

    public KeyManagement keyManagement;
    public GlobalSettings settings;
    public ConnectionFactory settingsConnection;
    public EcosystemMaster ecosystemMaster;
    public EcosystemResponse ecosystemResponse;

    public ProductMaster() throws Exception {
        keyManagement = new KeyManagement();
        settings = new GlobalSettings();
        settingsConnection = new ConnectionFactory();
        ecosystemMaster = new EcosystemMaster(settingsConnection, settings);
        ecosystemResponse = new EcosystemResponse(settingsConnection, settings, ecosystemMaster.basicProducerKerberos);
    }

    /**
     * @return Result
     */
    @RequestMapping(value = "/generateKey", method = GET)
    public String generateKey(@RequestHeader Map<String, String> headers) throws Exception {
        LOGGER.info("/refresh API");
        String returnVal;
        if (ACCESS_KEY.isEmpty()) {
            ACCESS_KEY = keyManagement.generateKey();
            returnVal = ACCESS_KEY;
        } else {
            returnVal = "KEY ALREADY GENERATED";
        }

        return returnVal;
    }

    /**
     * Refresh product matrix and master
     *
     * @return Result
     */
    @Operation(summary = "Refresh internal scoring and data services and reconnect to data sources.")
    @RequestMapping(value = "/refresh", method = GET)
    public String refresh(@RequestHeader Map<String, String> headers) throws Exception {
        LOGGER.info("/refresh API");

        if (ecosystemMaster.session != null)
            ecosystemMaster.session.close();

        if (settingsConnection.mongoClient != null)
            settingsConnection.mongoClient.close();

        if (settingsConnection.mongoClient2 != null)
            settingsConnection.mongoClient2.close();

        if (ecosystemMaster.preLoadCorpora.settingsConnection.mongoClient != null)
            ecosystemMaster.preLoadCorpora.settingsConnection.mongoClient.close();

        if (ecosystemMaster.settingsConnection.mongoClient != null)
            ecosystemMaster.settingsConnection.mongoClient.close();
        if (ecosystemMaster.settingsConnection.mongoClient2 != null)
            ecosystemMaster.settingsConnection.mongoClient2.close();

        if (ecosystemResponse.preLoadCorpora.settingsConnection.mongoClient != null)
            ecosystemResponse.preLoadCorpora.settingsConnection.mongoClient.close();

        if (ecosystemResponse.settingsConnection.mongoClient != null)
            ecosystemResponse.settingsConnection.mongoClient.close();
        if (ecosystemResponse.settingsConnection.mongoClient2 != null)
            ecosystemResponse.settingsConnection.mongoClient2.close();

        this.settings = new GlobalSettings();
        settings = new GlobalSettings();
        // ProductMaster pM = new ProductMaster();

        if (ecosystemMaster.modelNames != null)
            ecosystemMaster.modelNames = new JSONArray();
        if (ecosystemMaster.modelNamesObj != null)
            ecosystemMaster.modelNamesObj = new JSONObject();
        if (ecosystemMaster.models != null)
            ecosystemMaster.models = new EasyPredictModelWrapper[200];

        this.settingsConnection = new ConnectionFactory();
        this.ecosystemMaster = new EcosystemMaster(settingsConnection, this.settings);
        this.ecosystemMaster.settings = this.settings;

        this.ecosystemResponse = new EcosystemResponse(settingsConnection, this.settings, this.ecosystemMaster.basicProducerKerberos);
        ecosystemResponse.settings = this.settings;

        LOGGER.info("refresh:Models: " + this.settings.getMojo());
        LOGGER.info("refresh:Epsilon: " + this.settings.getEpsilon());
        try {
            return "{\"message\":\"Success\"}";
        } catch (NullPointerException e) {
            JSONObject error = new JSONObject();
            error.put("ErrorMessage", "Refresh failed, please check database or other connections, models, etc: " + e.getMessage());
            LOGGER.info("refresh: Reload Failed");
            return error.toString();
        }
    }

    /**************************************************************************************************************/

    /**
     * Score values in batch abd write back...
     * Process and score individual records. Param string example:
     * {"pulse_responder_list":[],"execution_type": "internal", "database": "behavior", "collection":"budget_messages_segment", "threads": 2, "find": "{}", "database_out":"behavior", "collection_out": "budget_messages_out", "campaign":"dynamic_engagement", "number_of_offers": 1, "userid": "batch","options": "", contextual_variables: {"contextual_variable_one_name":"personality", "contextual_variable_two_name":""}}*
     * OR when external runtimes are used:
     * {"pulse_responder_list":[{"server":"http://localhost:8092"},{"server":"http://localhost:8093"},{"server":"http://localhost:8094"}], "execution_type": "internal", "database": "behavior", "collection": "ucn_baseid_anon", "threads": 7, "find": "{}", "database_out": "behavior", "collection_out": "ucn_baseid_anon_scored", "campaign": "dynamic_engagement", "number_of_offers": 10, "userid": "batch", "options": "", contextual_variables: {"contextual_variable_one_name":"personality", "contextual_variable_two_name":""}}
     *
     ** @return Result
     */
    @Operation(description = "Process and score individual records. Param string example:\n" +
            "{\"pulse_responder_list\":[],\"execution_type\": \"internal\", \"database\": \"behavior\", \"collection\":\"budget_messages_segment\", \"threads\": 2, \"find\": \"{}\", \"database_out\":\"behavior\", \"collection_out\": \"budget_messages_out\", \"campaign\":\"dynamic_engagement\", \"number_of_offers\": 1, \"userid\": \"batch\",\"options\": \"\", contextual_variables: {\"contextual_variable_one_name\":\"personality\", \"contextual_variable_two_name\":\"\"}}*\n" +
            "OR when external runtimes are used:\n" +
            "{\"pulse_responder_list\":[{\"server\":\"http://localhost:8092\"},{\"server\":\"http://localhost:8093\"},{\"server\":\"http://localhost:8094\"}], \"execution_type\": \"internal\", \"database\": \"behavior\", \"collection\": \"ucn_baseid_anon\", \"threads\": 7, \"find\": \"{}\", \"database_out\": \"behavior\", \"collection_out\": \"ucn_baseid_anon_scored\", \"campaign\": \"dynamic_engagement\", \"number_of_offers\": 10, \"userid\": \"batch\", \"options\": \"\", contextual_variables: {\"contextual_variable_one_name\":\"personality\", \"contextual_variable_two_name\":\"\"}}" +
            "", summary = "Process and score individual records in batch mode.")

    @RequestMapping(value = "/processBatch", method = RequestMethod.POST)
    public String processBatch(@RequestHeader Map<String, String> headers,
                               @RequestBody String request) throws Exception {
        LOGGER.info("/processBatch ");
        JSONObject result;
        try {
            JSONObject paramsObj = new JSONObject(request);
            EcosystemBatch ecosystemBatch = new EcosystemBatch(settings, settingsConnection, ecosystemMaster, ecosystemResponse);
            result = ecosystemBatch.processBatch(paramsObj);
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.error("PredictorMaster:processBatch:E000: Param error: " + e);
            return ("{\"ErrorMessage\":\"Error: " + e + "\"}");
        }
        return result.toString();
    }

    /**************************************************************************************************************/

    /**
     * AWS Sagemaker endpoint
     * @param request
     * @return
     * @throws Exception
     */
    @Operation(summary = "Invocation endpoint for AWS SageMaker.")
    @RequestMapping(value = "/invocations", method = RequestMethod.POST)
    public String invoke(@RequestHeader Map<String, String> headers,
                         @RequestBody String request) throws Exception {
        LOGGER.info("/invocations API");
        JSONObject predictResult = new JSONObject();
        try {
            JSONObject inpObj = new JSONObject(request);
            String params = inpObj.getString("params");
            String campaign = inpObj.getString("campaign");
            String subcampaign = inpObj.getString("subcampaign");
            String channel = inpObj.getString("channel");
            int numberoffers = inpObj.getInt("numberoffers");
            String userid = inpObj.getString("userid");
            String customer = inpObj.getString("customer");

            JSONObject paramsParams = new JSONObject();
            try {
                paramsParams = new JSONObject(JSONDecode.decode(params));
            } catch (org.json.JSONException e) {
                LOGGER.info("/offerRecommendations malformed params JSON input: " + params);
                return paramsParams.put("ErrorMessage", e).toString().intern();
            }

            /* Setup values from input params that will be placed in */
            JSONObject param = new JSONObject();
            String uuid = generateUUID();
            // param.put("headers", headers);
            param.put("uuid", uuid);
            param.put("name", campaign);
            param.put("customer", customer);
            param.put("subcampaign", subcampaign);
            param.put("channel", channel);
            param.put("subname", subcampaign);
            param.put("resultcount", numberoffers);
            param.put("userid", userid);
            /* this is needed to not cause a stack overflow as adding current value of json object */
            JSONObject inParam = new JSONObject(param.toString());
            param.put("api_params", inParam);
            param = ValidateParams.getLookupFromParams(settings, param, customer);

            /* Set defaults for model and paramneters from database */
            param.put("mojo", "1");
            param.put("dbparam", true);

            /* Obtain default epsilon from properties or obtain from input params */
            if (!paramsParams.has("mab")) {
                JSONObject mabParam = new JSONObject();
                mabParam.put("class", "mabone");
                mabParam.put("epsilon", settings.getEpsilon());
                param.put("mab", mabParam);
            } else {
                param.put("mab", paramsParams.getJSONObject("mab"));
            }
            param.put("in_params", paramsParams);

            /* Primary prediction from EcosystemMaster.getPredictionResult */
            predictResult = ecosystemMaster.getPredictionResult(param);
            if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
            if (predictResult.has("ErrorMessage")) {
                predictResult.put("error", 1);
            }

            predictResult.remove("predict_result");
        } catch (Exception e) {
            e.printStackTrace();
            predictResult.put("ErrorMessage", e.getMessage());
        }
        return predictResult.toString();
    }


    /**
     * Prediction case is determined from properties file setup: mojo's, feature store, and other settings.
     * <p>
     * Recommender Case:
     * From paramsParams - balance enquiry example: {msisdn:0828811817,in_balance:50,voice_balance:12,data_balance:400,n_offers:1}
     * <p>
     * Recommender Case:
     * {'name':'rechargerecommender', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'msisdn',value:849999330}, param:{key:'in_recharge', value:100}, resultcount:2}
     *
     * @param campaign     campaign
     * @param subcampaign  subcampaign
     * @param customer     customer
     * @param channel      channel
     * @param numberoffers numberoffers
     * @return Result
     */
    @Operation(summary = "Provide offers that form part of a campaign for particular customer. When no feature store is present, use this in params: {'input': ['key1','key2'],'value': ['value1', 'value2']}")
    // @ApiResponse(code = 200, message = "Recommender successfully completed")
    @RequestMapping(value = "/offerRecommendations", method = GET)
    @ResponseStatus(HttpStatus.OK)
    public String getOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(name = "campaign", defaultValue = "") String campaign,
                                          @RequestParam(name = "subcampaign", defaultValue = "", required = false) String subcampaign,
                                          @RequestParam(name = "customer", defaultValue = "") String customer,
                                          @RequestParam(name = "channel", defaultValue = "") String channel,
                                          @RequestParam(name = "numberoffers", defaultValue = "", required = false) int numberoffers,
                                          @RequestParam(name = "userid", defaultValue = "") String userid,
                                          @RequestParam(name = "params", defaultValue = "", required = false) String jsonParams) throws Exception {
        LOGGER.info("/offerRecommendations API");

        JSONObject paramsParams = new JSONObject();
        try {
            paramsParams = new JSONObject(JSONDecode.decode(jsonParams));
        } catch (org.json.JSONException e) {
            LOGGER.info("/offerRecommendations malformed params JSON input: " + jsonParams);
            return paramsParams.put("ErrorMessage", e).toString();
        }

        /* Setup values from input params that will be placed in */
        JSONObject param = new JSONObject();
        String uuid = generateUUID();
        param.put("headers", headers);
        param.put("uuid", uuid);
        param.put("customer", customer);
        param.put("name", campaign);
        param.put("subcampaign", subcampaign);
        param.put("channel", channel);
        param.put("subname", subcampaign);
        param.put("resultcount", numberoffers);
        param.put("userid", userid);
        /* this is needed to not cause a stack overflow as adding current value of json object */
        JSONObject inParam = new JSONObject(param.toString());
        param.put("api_params", inParam);

        /** Set defaults for model and paramneters from database */
        param.put("in_params", paramsParams);
        if (paramsParams.has("input")) {
            param.put("input", paramsParams.getJSONArray("input"));
            param.put("value", paramsParams.getJSONArray("value"));
            param.put("lookup", new JSONObject().put("value", customer).put("key", "customer"));
            param.put("dbparam", false);
        } else {
            param.put("dbparam", true);
            param = ValidateParams.getLookupFromParams(settings, param, customer);
        }

        param.put("mojo", "1");

        /** Obtain default epsilon from properties or obtain from input params */
        if (!paramsParams.has("mab")) {
            JSONObject mabParam = new JSONObject();
            mabParam.put("class", "mabone");
            mabParam.put("epsilon", settings.getEpsilon());
            param.put("mab", mabParam);
        } else {
            param.put("mab", paramsParams.getJSONObject("mab"));
        }

        /** Primary prediction from EcosystemMaster.getPredictionResult */
        JSONObject predictResult = new JSONObject();
        predictResult = ecosystemMaster.getPredictionResult(param);
        if (param.has("in_params")) predictResult.put("in_params", param.getJSONObject("in_params"));
        if (predictResult.has("ErrorMessage")) {
            predictResult.put("error", 1);
        }

        predictResult.remove("predict_result");
        return predictResult.toString();
    }

    /**
     * Update offers taken up by customers/msisdn
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @Operation(description = "Update response based on recommendation accepted:" +
            "{\"uuid\":\"dcb54a23-0737-4768-845d-48162598c0f7\",\"offers_accepted\":[{\"offer_name\":\"GSM_999_A\"}],\"channel_name\":\"USSD\",\"transaction_id\":\"uuid:0aa9140a-755e-48de-84a2-0a67451804f7\"}" +
            "", summary = "Generic prediction scoring endine for recommenders.")
    @RequestMapping(value = "/offerRecommendations", method = PUT)
    @ResponseStatus(HttpStatus.OK)
    public String putOfferRecommendations(@RequestHeader Map<String, String> headers,
                                          @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/offerRecommendations PUT API");

        String response;
        try {
            // Approach 1: return detail from response
            JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
            if (responseObj != null)
                response = responseObj.getString("uuid");
            else
                response = "none";

            // Approach 2: return uuid only
            // String response = String.valueOf(ecosystemResponse.putResponse(JSONDecode.decode(documentJSON)));
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject error = new JSONObject().put("ErrorMessage", "Validate that uuid is available in log. " + e.getMessage());
            response = error.toString();
        }
        return "{\"message\":\"" + response + "\"}";
    }


    /**************************************************************************************************************/

    /**
     * CORE RUNTIME FEATURES FROM GENERATION 2
     */

    /**
     * Model details. Example parameter: {'mojo':'my_mojo.zip'}
     *
     * @param valueJSON JSON parameter: {'mojo':'my_mojo.zip'}
     * @return Result of model details
     * @throws IOException Error
     */
    @Operation(summary = "Model details. Example parameter: {'mojo':'my_mojo.zip'}")
    @RequestMapping(value = "/modelDetail", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String modelDetail(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "model", defaultValue = "{'mojo':'my_mojo.zip'}") String valueJSON)
            throws Exception {
        LOGGER.info("/modelDetail params: " + valueJSON);
        ModelMojoWorkerH2O uPd = new ModelMojoWorkerH2O();
        return uPd.modelDetail(JSONDecode.decode(valueJSON)).toString();
    }

    /**
     * Score model mojo from parameters
     *
     * @param valueJSON JSON Parameter: {'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']}
     * @param detail    Detail to return: all, basic or none
     * @return Result
     * @throws IOException Error
     */
    @Operation(description = "Perform basic prediction on model with detail: none, basic or all. Example parameter: " +
            "{'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']}",
            summary = "Basic scoring endine.")
    @RequestMapping(value = "/runModelMojo", method = RequestMethod.GET)
    public String runModelMojo(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "value",
                    defaultValue = "{'mojo':'model_mojo.zip','input': ['x','y'],'value': ['val_x', 'val_y']} ") String valueJSON,
            @RequestParam(name = "detail",
                    defaultValue = "all") String detail)
            throws Exception {
        LOGGER.info("/runModelMojo params: " + valueJSON);

        JSONObject logStats = new JSONObject();
        logStats = ActivityLog.logStart(logStats);

        String uuid = UUID.randomUUID().toString();
        ValidateParams vp = new ValidateParams();

        JSONObject params = vp.validateRunModelMojoParams(JSONDecode.decode(valueJSON));
        if (params != null) {
            RunModelMojo m = new RunModelMojo(valueJSON);
            JSONObject predictResult = new JSONObject(m.runModelMojo(valueJSON, detail));
            logStats = ActivityLog.logStop(logStats);

            MongoCollection loggingCollection = settingsConnection.connectMongoDB(settings.getLoggingDatabase(), settings.getLoggingCollection());
            if (loggingCollection != null)
                addLoggingAsync(loggingCollection, params, predictResult, logStats, uuid);

            return String.valueOf(predictResult);
        } else {
            return "{\"ErrorMessage\":\"PredictorMaster:runModelMojo:E001: No 'input' parameter, or no data for input.\"}";
        }
    }


    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @Operation(description = "Perform prediction on pre-loaded model with detail and push onto Kafka topic: none, basic or all. Perform a database lookup if properties file has been set. " +
            "Example parameter: Example: {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }" +
            "", summary = "Perform prediction on pre-loaded model with detail and push onto Kafka topic")
    @RequestMapping(value = "/predictorResponsePreLoadKafKa", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String predictorResponsePreLoadKafKa(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "value",
                    defaultValue = "{'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'},'mojo':'1', 'input':['x','y'], 'value':['val_x','val_y']} " +
                            "OR {'name':'predict1', 'kafka':{'TOPIC_NAME':'ecosystem1','log':'true'}, 'mojo':'1', 'dbparam':true, lookup:{key:'customer',value:'1234567890'} }") String valueJSON,
            @RequestParam(name = "detail",
                    defaultValue = "none") String detail) {
        LOGGER.info("/predictorResponsePreLoadKafKa params: " + valueJSON);
        try {
            JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
            return ecosystemMaster.getPredictionResultToKafka(params);
        } catch (Exception e) {
            LOGGER.error("PredictorMaster:predictorResponsePreLoadKafKa:E000: Param error: " + e);
            return "{\"ErrorMessage\":\"PredictorMaster:predictorResponsePreLoadKafKa:E000-1: Parameter error.\"}";
        }

    }

    /**
     * Score model from pre-loaded mojo as set in the properties file
     *
     * @param valueJSON Example: {'name':'predict1', 'mojo':'1','dbparam':true, lookup:{key:'customer_id',value:724578004}} OR if parameter is not
     *                  from database use: {'name':'predict1', 'mojo':'1..3','dbparam':false, 'input':['x','y'], 'value':['val_x','val_y']}
     *                  Use x:n to define the number of predictions to return as primary result, if the overall probability is not used.
     *                  Optional 'resultcount':3  if not present in parameter, then return one item
     * @param detail    Detail to return: all, basic or none
     * @return Result
     */
    @Operation(description = "Perform prediction on pre-loaded model with detail: none, basic or all. Perform a database lookup if properties file has been set. " +
            "The predictor parameters are broken into two types namely, requiring all parameters via API or requiring a lookup key via API and extracting parameters " +
            "from a data source." +
            "Use this format for input prams only:" +
            "{'name':'predict1', 'mojo':1,'dbparam':false,'input': ['x','y'],'value': ['val_x', 'val_y'], lookup:{'key':'', 'value':''}}" +
            "Use this approach for inputs from data source:" +
            "{'name':'predict1', 'mojo':'model_mojo.zip','dbparam':true, lookup:{key:'customer',value:1234567890}} " +
            "If there is post-scoring logic, then ise this configuration:" +
            "{'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}}" +
            "", summary = "Perform prediction on pre-loaded model with detail.")
    @RequestMapping(value = "/predictorResponsePreLoad", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.OK)
    public String predictorResponsePreLoad(
            @RequestHeader Map<String, String> headers,
            @RequestParam(name = "value",
                    defaultValue = "{'name':'predict1', 'mojo':'1..16','dbparam':false, 'input':['x','y'], 'value':['val_x','val_y'], 'x':1} " +
                            "OR {'name':'predict1', 'mojo':'1','dbparam':true, lookup:{key:'customer',value:'1234567890'}} + " +
                            "OR {'name':'predict1', 'mojo':'1','mab':{'class':'mabone', 'epsilon':0.4},'dbparam':true, lookup:{key:'customer',value:1234567890}, param:{key:'value_field', value:30}, resultcount:3}") String valueJSON,
            @RequestParam(name = "detail",
                    defaultValue = "all") String detail) throws Exception {
        LOGGER.info("/predictorResponsePreLoad params: " + valueJSON);
        String uuid = UUID.randomUUID().toString();
        JSONObject params = new JSONObject(JSONDecode.decode(valueJSON));
        params.put("uuid", uuid);
        return ecosystemMaster.getPredictionResult(params).toString();
    }

    /**
     * Update responses based on predictions.
     *
     * @param documentJSON documentJSON
     * @return Result
     */
    @Operation(description = "Update response based on predictions accepted:" +
            "{\"uuid\":\"dcb54a23-0737-4768-845d-48162598c0f7\",\"offers_accepted\":[{\"offer_name\":\"GSM_999_A\"}],\"channel_name\":\"USSD\",\"transaction_id\":\"uuid:0aa9140a-755e-48de-84a2-0a67451804f7\"}" +
            "", summary = "Update response based on predictions accepted")

    @RequestMapping(value = "/response", method = PUT)
    public String response(@RequestHeader Map<String, String> headers,
                           @RequestParam(name = "document", defaultValue = "") String documentJSON) throws IOException, ParseException {
        LOGGER.info("/response PUT API");

        JSONObject responseObj = ecosystemResponse.putResponseReturnDetail(JSONDecode.decode(documentJSON));
        String response = responseObj.getString("uuid");

        return "{\"message\":\"" + response + "\"}";
    }

    /**************************************************************************************************************/

}
