package outbackcdx;


import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import outbackcdx.NanoHTTPD.Response;
import outbackcdx.WbCdxApi.JsonFormat;
import outbackcdx.WbCdxApi.OutputFormat;
import outbackcdx.WbCdxApi.TextFormat;
import outbackcdx.auth.Permission;

import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.System.out;
import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Method.*;
import static outbackcdx.NanoHTTPD.Response.Status.*;
import static outbackcdx.Web.*;

import org.rocksdb.TransactionLogIterator;
import org.rocksdb.TransactionLogIterator.BatchResult;

class Webapp implements Web.Handler {
    private final boolean verbose;
    private final DataStore dataStore;
    private final Web.Router router;
    private final Map<String,Object> dashboardConfig;
    private final ArrayList<FilterPlugin> filterPlugins;

    private static ServiceLoader<FilterPlugin> fpLoader = ServiceLoader.load(FilterPlugin.class);

    private Response configJson(Web.Request req) {
        return jsonResponse(dashboardConfig);
    }

    private Response listAccessPolicies(Web.Request req) throws IOException, Web.ResponseException {
        return jsonResponse(getIndex(req).accessControl.listPolicies());
    }

    private Response deleteAccessRule(Web.Request req) throws IOException, Web.ResponseException, RocksDBException {
        long ruleId = Long.parseLong(req.param("ruleId"));
        boolean found = getIndex(req).accessControl.deleteRule(ruleId);
        return found ? ok() : notFound();
    }

    Webapp(DataStore dataStore, boolean verbose, Map<String, Object> dashboardConfig) {
        this.dataStore = dataStore;
        this.verbose = verbose;
        this.dashboardConfig = dashboardConfig;

        this.filterPlugins = new ArrayList<FilterPlugin>();
        if (FeatureFlags.filterPlugins()) {
            System.out.println("Loading plugins");
            for (FilterPlugin f : this.fpLoader) {
                System.out.println("Loaded plugin");
                this.filterPlugins.add(f);
            }
        }

        router = new Router();
        router.on(GET, "/", interpolated("dashboard.html"));
        router.on(GET, "/api", serve("api.html"));
        router.on(GET, "/api.js", serve("api.js"));
        router.on(GET, "/add.svg", serve("add.svg"));
        router.on(GET, "/database.svg", serve("database.svg"));
        router.on(GET, "/outback.svg", serve("outback.svg"));
        router.on(GET, "/favicon.ico", serve("outback.svg"));
        router.on(GET, "/swagger.json", serve("swagger.json"));
        router.on(GET, "/lib/vue-router/2.0.0/vue-router.js", serve("lib/vue-router/2.0.0/vue-router.js"));
        router.on(GET, "/lib/vue/" + version("org.webjars.npm", "vue") + "/vue.js", serve("/META-INF/resources/webjars/vue/" + version("org.webjars.npm", "vue") + "/dist/vue.js"));
        router.on(GET, "/lib/lodash/" + version("org.webjars", "lodash") + "/lodash.min.js", serve("/META-INF/resources/webjars/lodash/" + version("org.webjars", "lodash") + "/lodash.min.js"));
        router.on(GET, "/lib/moment/" + version("org.webjars.npm", "moment") + "/moment.min.js", serve("/META-INF/resources/webjars/moment/" + version("org.webjars.npm", "moment") + "/min/moment.min.js"));
        router.on(GET, "/lib/pikaday/" + version("org.webjars.npm", "pikaday") + "/pikaday.js", serve("/META-INF/resources/webjars/pikaday/" + version("org.webjars.npm", "pikaday") + "/pikaday.js"));
        router.on(GET, "/lib/pikaday/" + version("org.webjars.npm", "pikaday") + "/pikaday.css", serve("/META-INF/resources/webjars/pikaday/" + version("org.webjars.npm", "pikaday") + "/css/pikaday.css"));
        router.on(GET, "/lib/redoc/" + version("org.webjars.bower", "redoc") + "/redoc.min.js", serve("/META-INF/resources/webjars/redoc/" + version("org.webjars.bower", "redoc") + "/dist/redoc.min.js"));
        router.on(GET, "/api/collections", request1 -> listCollections(request1));
        router.on(GET, "/config.json", req1 -> configJson(req1));
        router.on(GET, "/<collection>", request -> query(request));
        router.on(POST, "/<collection>", request -> post(request), Permission.INDEX_EDIT);
        router.on(POST, "/<collection>/delete", request -> delete(request), Permission.INDEX_EDIT);
        router.on(GET, "/<collection>/stats", req2 -> stats(req2));
        router.on(GET, "/<collection>/captures", request -> captures(request));
        router.on(GET, "/<collection>/aliases", request -> aliases(request));
        router.on(GET, "/<collection>/changes", request -> changeFeed(request));
        router.on(GET, "/<collection>/sequence", request -> sequence(request));
        router.on(POST, "/<collection>/truncate_replication", request -> flushWal(request));

        if (FeatureFlags.experimentalAccessControl()) {
            router.on(GET, "/<collection>/ap/<accesspoint>", request -> query(request));
            router.on(GET, "/<collection>/ap/<accesspoint>/check", request1 -> checkAccess(request1));
            router.on(POST, "/<collection>/ap/<accesspoint>/check", request -> checkAccessBulk(request));
            router.on(GET, "/<collection>/access/rules", request -> listAccessRules(request));
            router.on(POST, "/<collection>/access/rules", request -> postAccessRules(request), Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/rules/new", request1 -> getNewAccessRule(request1), Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/rules/<ruleId>", req -> getAccessRule(req));
            router.on(DELETE, "/<collection>/access/rules/<ruleId>", req1 -> deleteAccessRule(req1), Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/policies", req1 -> listAccessPolicies(req1));
            router.on(POST, "/<collection>/access/policies", request -> postAccessPolicy(request), Permission.POLICIES_EDIT);
            router.on(GET, "/<collection>/access/policies/<policyId>", req -> getAccessPolicy(req));
        }
    }

    Response flushWal(Web.Request request) throws Web.ResponseException, IOException, RocksDBException{
        Index index = getIndex(request);
        index.flushWal();
        Map<String,Boolean> map = new HashMap<>();
        map.put("success", true);
        return jsonResponse(map);
    }

    Response listCollections(Web.Request request) {
        return jsonResponse(dataStore.listCollections());
    }

    Response stats(Web.Request req) throws IOException, Web.ResponseException {
        Index index = getIndex(req);
        Map<String,Object> map = new HashMap<>();
        map.put("estimatedRecordCount", index.estimatedRecordCount());
        Response response = new Response(Response.Status.OK, "application/json",
                GSON.toJson(map));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    Response captures(Web.Request request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);
        String key = request.param("key", "");
        long limit = Long.parseLong(request.param("limit", "1000"));
        List<Capture> results = StreamSupport.stream(index.capturesAfter(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Capture>toList());
        return jsonResponse(results);
    }

    Response aliases(Web.Request request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);
        String key = request.param("key", "");
        long limit = Long.parseLong(request.param("limit", "1000"));
        List<Alias> results = StreamSupport.stream(index.listAliases(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Alias>toList());
        return jsonResponse(results);
    }

    Response delete(Web.Request request) throws IOException {
        if(FeatureFlags.isSecondary() && !FeatureFlags.acceptsWrites()){
            return new Response(UNAUTHORIZED, "text/plain", "This node is running in secondary mode to an upstream primary, and will not accept writes.");
        }
        String collection = request.param("collection");
        final Index index = dataStore.getIndex(collection);
        BufferedReader in = new BufferedReader(new InputStreamReader(request.inputStream()));
        long deleted = 0;

        try (Index.Batch batch = index.beginUpdate()) {
            while (true) {
                String line = in.readLine();
                if (verbose) {
                    out.println("DELETE " + line);
                }
                if (line == null) break;
                if (line.startsWith(" CDX")) continue;

                try {
                    if (line.startsWith("@alias ")) {
                        throw new UnsupportedOperationException("Deleting of aliases is not yet implemented");
                    }

                    batch.deleteCapture(Capture.fromCdxLine(line));
                    deleted++;
                } catch (Exception e) {
                    return new Response(BAD_REQUEST, "text/plain", "At line: " + line + "\n" + formatStackTrace(e));
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Deleted " + deleted + " records\n");
    }

    Response post(Web.Request request) throws IOException {
        if(FeatureFlags.isSecondary() && !FeatureFlags.acceptsWrites()){
            return new Response(UNAUTHORIZED, "text/plain", "This node is running in secondary mode to an upstream primary, and will not accept writes.");
        }
        String collection = request.param("collection");
        boolean skipBadLines = "skip".equals(request.param("badLines", "error"));
        final Index index = dataStore.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(request.inputStream()));
        long added = 0;

        try (Index.Batch batch = index.beginUpdate()) {
            while (true) {
                String line = in.readLine();
                if (verbose) {
                    out.println(line);
                }
                if (line == null) break;
                if (line.startsWith(" CDX")) continue;

                try {
                    if (line.startsWith("@alias ")) {
                        String[] fields = line.split(" ");
                        String aliasSurt = UrlCanonicalizer.surtCanonicalize(fields[1]);
                        String targetSurt = UrlCanonicalizer.surtCanonicalize(fields[2]);
                        batch.putAlias(aliasSurt, targetSurt);
                        added++;
                    } else {
                        try  {
                            batch.putCapture(Capture.fromCdxLine(line));
                            added++;
                        } catch (Exception e) {
                            if (skipBadLines) {
                                System.err.println("skipping bad cdx line: " + line);
                                e.printStackTrace();
                            } else {
                                throw e;
                            }
                        }
                    }
                } catch (Exception e) {
                    return new Response(BAD_REQUEST, "text/plain", "At line: " + line + "\n" + formatStackTrace(e));
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Added " + added + " records\n");
    }

    private String formatStackTrace(Exception e) {
        StringWriter stacktrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stacktrace));
        return stacktrace.toString();
    }

    Response sequence(Web.Request request) throws IOException, ResponseException {
        final Index index = getIndex(request);
        String output = String.valueOf(index.db.getLatestSequenceNumber());
        return new Response(OK, "text/html", output);
    }
    
    Response changeFeed(Web.Request request) throws Web.ResponseException, IOException {
        String collection = request.param("collection");
        long since = Long.parseLong(request.param("since"));
        final Index index = getIndex(request);

        if (verbose) {
            out.println(String.format("Received request %s. Retrieved deltas for collection <%s> since sequenceNumber %s", request, collection, since));
        }

        Response response = new Response(OK, "application/json", outputStream -> {
            try(TransactionLogIterator logReader = index.getUpdatesSince(since)) {
                JsonWriter output = GSON.newJsonWriter(new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8)));
                output.beginArray();

                while (logReader.isValid()) {
                    BatchResult batch = logReader.getBatch();
                    // only return results _after_ the 'since' number
                    if ((Long) batch.sequenceNumber() < since) {
                        logReader.next();
                        continue;
                    }

                    output.beginObject();
                    output.name("sequenceNumber").value(((Long) batch.sequenceNumber()).toString());
                    String base64WriteBatch;
                    try {
                        base64WriteBatch = Base64.getEncoder().encodeToString(batch.writeBatch().data());
                    } catch (RocksDBException e) {
                        throw new IOException(e);
                    }
                    output.name("writeBatch").value(base64WriteBatch);
                    output.endObject();

                    logReader.next();
                }
                output.endArray();
                output.flush();
            }
        });
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;

    }

    Response query(Web.Request request) throws IOException, Web.ResponseException {
        if (verbose) {
            out.println(request);
        }
    	
        Index index = getIndex(request);
        Map<String,String> params = request.params();
        if (params.containsKey("q")) {
            return XmlQuery.queryIndex(request, index, this.filterPlugins);
        } else if (params.containsKey("url")) {
            return WbCdxApi.queryIndex(request, index, this.filterPlugins);
        } else {
            return collectionDetails(index.db);
        }
    }

    private Index getIndex(Web.Request request) throws IOException, Web.ResponseException {
        String collection = request.param("collection");
        final Index index = dataStore.getIndex(collection);
        if (index == null) {
            throw new Web.ResponseException(new Response(NOT_FOUND, "text/plain", "Collection does not exist"));
        }
        return index;
    }

    private Response collectionDetails(RocksDB db) {
        String page = "<form>URL: <input name=url type=url><button type=submit>Query</button></form>\n<pre>";
        try {
            page += db.getProperty("rocksdb.stats");
            page += "\nEstimated number of records: " + db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            page += e.toString();
            e.printStackTrace();
        }
        return new Response(OK, "text/html", page);
    }

    private <T> T fromJson(Web.Request request, Class<T> clazz) {
        return GSON.fromJson(new InputStreamReader(request.inputStream(), UTF_8), clazz);
    }

    private Response getAccessPolicy(Web.Request req) throws IOException, Web.ResponseException {
        long policyId = Long.parseLong(req.param("policyId"));
        AccessPolicy policy = getIndex(req).accessControl.policy(policyId);
        if (policy == null) {
            return notFound();
        }
        return jsonResponse(policy);
    }

    private Response postAccessPolicy(Web.Request request) throws IOException, Web.ResponseException, RocksDBException {
        AccessPolicy policy = fromJson(request, AccessPolicy.class);
        Long id = getIndex(request).accessControl.put(policy);
        return id == null ? ok() : created(id);
    }

    private Response postAccessRules(Web.Request request) throws IOException, Web.ResponseException, RocksDBException {
        AccessControl accessControl = getIndex(request).accessControl;

        // parse rules
        List<AccessRule> rules;
        boolean single = false;
        if ("application/xml".equals(request.header("content-type"))) {
            try {
                rules = AccessRuleXml.parseRules(request.inputStream());
            } catch (XMLStreamException e) {
                return new Response(BAD_REQUEST, "text/plain", formatStackTrace(e));
            }
        } else { // JSON format
            JsonReader reader = GSON.newJsonReader(new InputStreamReader(request.inputStream(), UTF_8));
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                rules = new ArrayList<>();
                while (reader.hasNext()) {
                    AccessRule rule = GSON.fromJson(reader, AccessRule.class);
                    rules.add(rule);
                }
                reader.endArray();
            } else { // single rule
                rules = Arrays.asList((AccessRule)GSON.fromJson(reader, AccessRule.class));
                single = true;
            }
        }

        // validate rules
        List<AccessRuleError> errors = new ArrayList<>();
        for (AccessRule rule: rules) {
            errors.addAll(rule.validate());
        }

        // return an error response if any failed
        if (!errors.isEmpty()) {
            Response response = jsonResponse(errors);
            response.setStatus(BAD_REQUEST);
            return response;
        }

        // save all the rules
        List<Long> ids = new ArrayList<>();
        for (AccessRule rule : rules) {
            ids.add(accessControl.put(rule, request.username()));
        }

        // return successful response
        if (single) {
            Long id = ids.get(0);
            return id == null ? ok() : created(id);
        } else {
            return jsonResponse(ids);
        }
    }

    private Response ok() {
        return new Response(OK, null, "");
    }

    private Response created(long id) {
        Map<String,String> map = new HashMap<>();
        map.put("id", Long.toString(id));
        return new Response(CREATED, "application/json", GSON.toJson(map));
    }

    private Response getAccessRule(Web.Request req) throws IOException, Web.ResponseException, RocksDBException {
        Index index = getIndex(req);
        Long ruleId = Long.parseLong(req.param("ruleId"));
        AccessRule rule = index.accessControl.rule(ruleId);
        if (rule == null) {
            return notFound();
        }
        return jsonResponse(rule);
    }

    private Response getNewAccessRule(Web.Request request) {
        AccessRule rule = new AccessRule();
        return jsonResponse(rule);
    }

    private Response listAccessRules(Web.Request request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);

        // search filter
        String search = request.param("search");
        List<AccessRule> rules = new ArrayList<>();
        for (AccessRule rule : index.accessControl.list()) {
            if (search == null || rule.contains(search)) {
                rules.add(rule);
            }
        }

        // sort rules
        String sort = request.param("sort", "id");
        if (sort.replaceFirst("^-", "").equals("surt")) {
            Comparator<AccessRule> cmp = Comparator.comparingInt(rule -> rule.pinned ? 0 : 1);
            cmp = cmp.thenComparing(rule -> rule.ssurtPrefixes().findFirst().orElse(""));
            cmp = cmp.thenComparingLong(rule -> rule.id);
            rules.sort(cmp);
        }
        if (sort.startsWith("-")) {
            Collections.reverse(rules);
        }

        // output format
        String type;
        String extension;
        RuleFormatter formatter;
        if (request.param("output", "json").equals("csv")) {
            type = "text/csv";
            extension = "csv";
            formatter = AccessRule::toCSV;
         } else {
            type = "application/json";
            extension = "json";
            formatter = AccessRule::toJSON;
        }
        Response response = new Response(OK, type, out -> {
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, UTF_8))) {
                formatter.format(rules, policyId -> index.accessControl.policy(policyId).name, bw);
            }
        });
        String filename = index.name.replace("\"", "") + "-access-rules." + extension;
        response.addHeader("Content-Disposition", "filename=\"" + filename + "\"");
        return response;
    }

    private interface RuleFormatter {
        void format(Collection<AccessRule> rules, Function<Long,String> policyNames, Writer out) throws IOException;
    }

    Response checkAccess(Web.Request request) throws IOException, ResponseException {
        String accesspoint = request.param("accesspoint");
        String url = request.mandatoryParam("url");
        String timestamp = request.mandatoryParam("timestamp");

        Date captureTime = Date.from(LocalDateTime.parse(timestamp, Capture.arcTimeFormat).toInstant(ZoneOffset.UTC));
        Date accessTime = new Date();

        return jsonResponse(getIndex(request).accessControl.checkAccess(accesspoint, url, captureTime, accessTime));
    }

    public static class AccessQuery {
        public String url;
        public String timestamp;
    }

    Response checkAccessBulk(Web.Request request) throws IOException, ResponseException {
        String accesspoint = request.param("accesspoint");
        Index index = getIndex(request);

        AccessQuery[] queries = fromJson(request, AccessQuery[].class);
        List<AccessDecision> responses = new ArrayList<>();

        for (AccessQuery query: queries) {
            Date captureTime = Date.from(LocalDateTime.parse(query.timestamp, Capture.arcTimeFormat).toInstant(ZoneOffset.UTC));
            Date accessTime = new Date();
            responses.add(index.accessControl.checkAccess(accesspoint, query.url, captureTime, accessTime));
        }

        return jsonResponse(responses);
    }

    @Override
    public Response handle(Web.Request request) throws Exception {
        Response response = router.handle(request);
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
        }
        return response;
    }

}
