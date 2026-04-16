package com.morpheusdata.cloudstack

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Slf4j
class CloudStackApiClient {

    private static final String HMAC_SHA1 = 'HmacSHA1'
    private final JsonSlurper jsonSlurper = new JsonSlurper()

    /**
     * Build a signed API request URL and execute it, returning the parsed JSON response.
     */
    Map callApi(String apiUrl, String apiKey, String secretKey, Map params) {
        try {
            def allParams = new LinkedHashMap(params)
            allParams['apikey'] = apiKey
            allParams['response'] = 'json'

            // Sort parameters alphabetically by lowercase key
            def sortedParams = allParams.sort { a, b -> a.key.toLowerCase() <=> b.key.toLowerCase() }

            // Build the query string for signing (lowercase keys, URL-encoded values)
            def signingString = sortedParams.collect { k, v ->
                "${URLEncoder.encode(k.toLowerCase(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8')}"
            }.join('&')

            def signature = signRequest(signingString, secretKey)

            def requestUrl = "${apiUrl}?${signingString}&signature=${signature}"
            log.debug("CloudStack API call: command=${params.command}")

            CloseableHttpClient httpClient = HttpClients.createDefault()
            try {
                def httpGet = new HttpGet(requestUrl)
                def response = httpClient.execute(httpGet)
                try {
                    def statusCode = response.statusLine.statusCode
                    def body = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)
                    if (statusCode >= 200 && statusCode < 300) {
                        def parsed = jsonSlurper.parseText(body)
                        return [success: true, data: parsed, statusCode: statusCode]
                    } else {
                        log.warn("CloudStack API error ${statusCode}: ${body}")
                        return [success: false, error: "HTTP ${statusCode}: ${body}", statusCode: statusCode]
                    }
                } finally {
                    response.close()
                }
            } finally {
                httpClient.close()
            }
        } catch (Exception e) {
            log.error("CloudStack API call failed: ${e.message}", e)
            return [success: false, error: e.message]
        }
    }

    /**
     * Compute HMAC-SHA1 signature of the query string, Base64-encoded then URL-encoded.
     */
    String signRequest(String queryString, String secretKey) {
        Mac mac = Mac.getInstance(HMAC_SHA1)
        SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA1)
        mac.init(keySpec)
        byte[] rawHmac = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8))
        String base64 = Base64.encoder.encodeToString(rawHmac)
        return URLEncoder.encode(base64, 'UTF-8')
    }

    Map listZones(String apiUrl, String apiKey, String secretKey, Map extraParams = [:]) {
        def params = [command: 'listZones', available: 'true'] + extraParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map listServiceOfferings(String apiUrl, String apiKey, String secretKey, Map extraParams = [:]) {
        def params = [command: 'listServiceOfferings'] + extraParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map listTemplates(String apiUrl, String apiKey, String secretKey, Map extraParams = [:]) {
        def params = [command: 'listTemplates', templatefilter: 'executable'] + extraParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map listNetworks(String apiUrl, String apiKey, String secretKey, Map extraParams = [:]) {
        def params = [command: 'listNetworks'] + extraParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map listVirtualMachines(String apiUrl, String apiKey, String secretKey, Map extraParams = [:]) {
        def params = [command: 'listVirtualMachines'] + extraParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map deployVirtualMachine(String apiUrl, String apiKey, String secretKey, Map deployParams) {
        def params = [command: 'deployVirtualMachine'] + deployParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map destroyVirtualMachine(String apiUrl, String apiKey, String secretKey, Map destroyParams) {
        def params = [command: 'destroyVirtualMachine'] + destroyParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map startVirtualMachine(String apiUrl, String apiKey, String secretKey, Map startParams) {
        def params = [command: 'startVirtualMachine'] + startParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map stopVirtualMachine(String apiUrl, String apiKey, String secretKey, Map stopParams) {
        def params = [command: 'stopVirtualMachine'] + stopParams
        return callApi(apiUrl, apiKey, secretKey, params)
    }

    Map queryAsyncJobResult(String apiUrl, String apiKey, String secretKey, String jobId) {
        def params = [command: 'queryAsyncJobResult', jobid: jobId]
        return callApi(apiUrl, apiKey, secretKey, params)
    }
}
