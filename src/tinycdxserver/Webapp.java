package tinycdxserver;


import com.google.gson.stream.JsonWriter;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import tinycdxserver.NanoHTTPD.IHTTPSession;
import tinycdxserver.NanoHTTPD.Response;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.System.out;
import static java.nio.charset.StandardCharsets.UTF_8;
import static tinycdxserver.Json.GSON;
import static tinycdxserver.NanoHTTPD.Method.*;
import static tinycdxserver.NanoHTTPD.Response.Status.*;
import static tinycdxserver.Web.*;

class Webapp implements Web.Handler {
    private final boolean verbose;
    private final DataStore dataStore;
    final Web.Router router = new Web.Router()
            .on(GET, "/", serve("dashboard.html"))
            .on(GET, "/api", serve("api.html"))
            .on(GET, "/api.js", serve("api.js"))
            .on(GET, "/add.svg", serve("add.svg"))
            .on(GET, "/database.svg", serve("database.svg"))
            .on(GET, "/outback.svg",  serve("outback.svg"))
            .on(GET, "/favicon.ico",  serve("outback.svg"))
            .on(GET, "/swagger.json", serve("swagger.json"))

            .on(GET, "/lib/vue-router/2.0.0/vue-router.js", serve("lib/vue-router/2.0.0/vue-router.js"))
            .on(GET, "/lib/vue/2.0.1/vue.js", serve("/META-INF/resources/webjars/vue/2.0.1/dist/vue.js"))
            .on(GET, "/lib/lodash/4.15.0/lodash.min.js", serve("/META-INF/resources/webjars/lodash/4.15.0/lodash.min.js"))
            .on(GET, "/lib/moment/2.15.2/moment.min.js", serve("/META-INF/resources/webjars/moment/2.15.2/min/moment.min.js"))
            .on(GET, "/lib/pikaday/1.4.0/pikaday.js", serve("/META-INF/resources/webjars/pikaday/1.4.0/pikaday.js"))
            .on(GET, "/lib/pikaday/1.4.0/pikaday.css", serve("/META-INF/resources/webjars/pikaday/1.4.0/css/pikaday.css"))
            .on(GET, "/lib/redoc/1.4.1/redoc.min.js", serve("/META-INF/resources/webjars/redoc/1.4.1/dist/redoc.min.js"))

            .on(GET, "/api/collections", this::listCollections)
            .on(GET, "/api/featureflags", this::featureFlags)
            .on(GET, "/<collection>", this::query)
            .on(POST, "/<collection>", this::post)
            .on(GET, "/<collection>/stats", this::stats)
            .on(GET, "/<collection>/captures", this::captures)
            .on(GET, "/<collection>/aliases", this::aliases);

    private Response featureFlags(IHTTPSession req) {
        return jsonResponse(FeatureFlags.asMap());
    }

    private Response listAccessPolicies(IHTTPSession req) throws IOException, Web.ResponseException {
        return jsonResponse(getIndex(req).accessControl.listPolicies());
    }

    private Response deleteAccessRule(IHTTPSession req) throws IOException, Web.ResponseException, RocksDBException {
        long ruleId = Long.parseLong(req.getParms().get("ruleId"));
        boolean found = getIndex(req).accessControl.deleteRule(ruleId);
        return found ? ok() : notFound();
    }

    Webapp(DataStore dataStore, boolean verbose) {
        this.dataStore = dataStore;
        this.verbose = verbose;

        if (FeatureFlags.experimentalAccessControl()) {
            router.on(GET, "/<collection>/ap/<accesspoint>", this::query)
                    .on(GET, "/<collection>/access/rules", this::listAccessRules)
                    .on(POST, "/<collection>/access/rules", this::createAccessRule)
                    .on(GET, "/<collection>/access/rules/<ruleId>", this::getAccessRule)
                    .on(DELETE, "/<collection>/access/rules/<ruleId>", this::deleteAccessRule)
                    .on(GET, "/<collection>/access/policies", this::listAccessPolicies)
                    .on(POST, "/<collection>/access/policies", this::postAccessPolicy)
                    .on(GET, "/<collection>/access/policies/<policyId>", this::getAccessPolicy);
        }
    }

    Response listCollections(IHTTPSession request) {
        return jsonResponse(dataStore.listCollections());
    }

    Response stats(IHTTPSession req) throws IOException, Web.ResponseException {
        Index index = getIndex(req);
        Map<String,Object> map = new HashMap<>();
        map.put("estimatedRecordCount", index.estimatedRecordCount());
        Response response = new Response(Response.Status.OK, "application/json",
                GSON.toJson(map));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    Response captures(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        List<Capture> results = StreamSupport.stream(index.capturesAfter(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Capture>toList());
        return jsonResponse(results);
    }

    Response aliases(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        List<Alias> results = StreamSupport.stream(index.listAliases(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Alias>toList());
        return jsonResponse(results);
    }

    Response post(IHTTPSession session) throws IOException {
        String collection = session.getParms().get("collection");
        final Index index = dataStore.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
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
                    } else {
                        batch.putCapture(Capture.fromCdxLine(line));
                    }
                    added++;
                } catch (Exception e) {
                    return new Response(Response.Status.BAD_REQUEST, "text/plain", e.toString() + "\nAt line: " + line);
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Added " + added + " records\n");
    }

    Response query(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        } else if (params.containsKey("url")) {
            return WbCdxApi.query(session, index);
        } else {
            return collectionDetails(index.db);
        }

    }

    private Index getIndex(IHTTPSession session) throws IOException, Web.ResponseException {
        String collection = session.getParms().get("collection");
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

    private <T> T fromJson(IHTTPSession session, Class<T> clazz) {
        return GSON.fromJson(new InputStreamReader(session.getInputStream(), UTF_8), clazz);
    }

    private Response getAccessPolicy(IHTTPSession req) throws IOException, Web.ResponseException {
        long policyId = Long.parseLong(req.getParms().get("policyId"));
        AccessPolicy policy = getIndex(req).accessControl.policy(policyId);
        if (policy == null) {
            return notFound();
        }
        return jsonResponse(policy);
    }

    private Response postAccessPolicy(IHTTPSession session) throws IOException, Web.ResponseException, RocksDBException {
        AccessPolicy policy = fromJson(session, AccessPolicy.class);
        Long id = getIndex(session).accessControl.put(policy);
        return id == null ? ok() : created(id);
    }

    private Response createAccessRule(IHTTPSession session) throws IOException, Web.ResponseException, RocksDBException {
        AccessRule rule = fromJson(session, AccessRule.class);
        Long id = getIndex(session).accessControl.put(rule);
        return id == null ? ok() : created(id);
    }

    private Response ok() {
        return new Response(OK, null, "");
    }

    private Response created(long id) {
        Map<String,String> map = new HashMap<>();
        map.put("id", Long.toString(id));
        return new Response(CREATED, "application/json", GSON.toJson(map));
    }

    private Response getAccessRule(IHTTPSession req) throws IOException, Web.ResponseException, RocksDBException {
        Index index = getIndex(req);
        Long ruleId = Long.parseLong(req.getParms().get("ruleId"));
        AccessRule rule = index.accessControl.rule(ruleId);
        if (rule == null) {
            return notFound();
        }
        return jsonResponse(rule);
    }

    private Response listAccessRules(IHTTPSession request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);
        Iterable<AccessRule> rules = index.accessControl.list();
        return new Response(OK, "application/json", outputStream -> {
            OutputStream out = new BufferedOutputStream(outputStream);
            JsonWriter json = GSON.newJsonWriter(new OutputStreamWriter(out, UTF_8));
            json.beginArray();
            for (AccessRule rule : rules) {
                GSON.toJson(rule, AccessRule.class, json);
            }
            json.endArray();
            json.close();
            out.flush();
        });
    }

    @Override
    public Response handle(IHTTPSession session) throws IOException, Web.ResponseException {
        Response response = router.handle(session);
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
        }
        return response;
    }

}