/*
 * OAI4Solr exposes your Solr indexes by adding a OAI2 protocol handler.
 *
 *     Copyright (c) 2011-2014  International Institute of Social History
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.socialhistoryservices.api.oai;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.DocList;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.SyntaxError;
import org.openarchives.oai2.*;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * OAIRequestHandler
 * <p/>
 * Parse the request as an OAI request; and perform an action.
 * <p/>
 * author: Lucien van Wouw <lwo@iisg.nl>
 */
public class OAIRequestHandler extends RequestHandlerBase {

    private final Log log = LogFactory.getLog(this.getClass());
    private final static String WT = "oai";  // The request writer


    @Override
    /**
     * Here we instantiate the oai request and validate the parameters.
     *
     */
    public void handleRequestBody(SolrQueryRequest request, SolrQueryResponse response) throws Exception {

        final NamedList<Object> list = request.getParams().toNamedList();
        list.add(CommonParams.WT, Parameters.getParam(CommonParams.WT, WT));

        // Add the xslt parameters from solrconfig
        final NamedList xslt_parameters = (NamedList) Parameters.getParam("xslt_parameters");
        list.addAll(xslt_parameters);

        request.setParams(SolrParams.toSolrParams(list));

        OAIPMHtype oai = new OAIPMHtype();
        response.add("oai", oai);


        VerbType verb;
        try {
            verb = VerbType.fromValue(request.getParams().get("verb"));
        } catch (Exception e) {
            Validation.error(response, String.format("Bad verb. Verb '%s' not implemented.", request.getParams().get("verb")),
                    OAIPMHerrorcodeType.BAD_VERB);
            return;
        }

        ResumptionToken oaiRequest = getRequest(request, verb);
        oai.setRequest(oaiRequest);
        if (!oaiRequest.isGood_resumptionToken()) {
            Validation.error(response, OAIPMHerrorcodeType.BAD_RESUMPTION_TOKEN);
            return;
        }

        switch (verb) {
            case IDENTIFY:
            case LIST_METADATA_FORMATS:
                response.getValues().remove("oai");
                oai = Parameters.getParam(verb);
                oai.setRequest(oaiRequest);
                response.add("oai", oai);
                break;
            case LIST_SETS:
                oai = Parameters.getParam(verb);
                if (oai == null) {
                    Validation.error(response, OAIPMHerrorcodeType.NO_SET_HIERARCHY);
                    return;
                }
                response.getValues().remove("oai");
                oai.setRequest(oaiRequest);
                response.add("oai", oai);
                break;
            case GET_RECORD:
            case LIST_IDENTIFIERS:
            case LIST_RECORDS:
                buildQuery(request, response, oaiRequest, verb, oai);
                break;
        }
    }

    private ResumptionToken getRequest(SolrQueryRequest request, VerbType verb) {

        final SolrParams params = request.getParams();
        final NamedList<Object> list = params.toNamedList();
        boolean token_good = true;
        ResumptionToken oaiRequest = null;
        try {
            oaiRequest = ResumptionToken.decodeResumptionToken(params.get("resumptionToken"));
        } catch (Exception e) {
            token_good = false;
        }
        if (oaiRequest == null) {
            oaiRequest = new ResumptionToken();
            oaiRequest.setVerb(verb);
            oaiRequest.setIdentifier(params.get("identifier"));
            oaiRequest.setMetadataPrefix(params.get("metadataPrefix"));
            oaiRequest.setFrom(params.get("from"));
            oaiRequest.setUntil(params.get("until"));
            oaiRequest.setSet(params.get("set"));
            oaiRequest.setResumptionToken(params.get("resumptionToken"));
            oaiRequest.setValue((String) Parameters.getParam("proxyurl"));
            oaiRequest.setResumptiontokenHealth(token_good);
        } else {
            oaiRequest.setVerb(verb);
            if (oaiRequest.getFrom() != null)
                list.add("from", oaiRequest.getFrom());
            if (oaiRequest.getUntil() != null)
                list.add("until", oaiRequest.getUntil());
            if (oaiRequest.getSet() != null)
                list.add("set", oaiRequest.getSet());
            if (oaiRequest.getMetadataPrefix() != null)
                list.add("metadataPrefix", oaiRequest.getMetadataPrefix());
        }
        request.setParams(SolrParams.toSolrParams(list));
        return oaiRequest;
    }

    private void buildQuery(SolrQueryRequest request, SolrQueryResponse response, ResumptionToken oaiRequest, VerbType verb, OAIPMHtype oai) throws java.text.ParseException, IOException, SyntaxError {

        List<String> q = new ArrayList<String>();
        Object maxrecords = Parameters.getParam("maxrecords_" + oaiRequest.getMetadataPrefix());
        int len = (maxrecords == null)
                ? (Integer) Parameters.getParam("maxrecords_default")
                : Integer.parseInt(String.valueOf(maxrecords));

        DocList docList = null;
        switch (verb) {
            case LIST_IDENTIFIERS:
            case LIST_RECORDS:
                if (!Validation.isValidMetadataPrefix(response, oaiRequest)) {
                    return;
                }

                if (!Validation.isValidDatestamp(oaiRequest.getFrom(), "from", response))
                    return;

                if (!Validation.isValidDatestamp(oaiRequest.getUntil(), "until", response)) {
                    return;
                }

                if (!Validation.isValidFromUntilCombination(oaiRequest.getFrom(), oaiRequest.getUntil(), response)) {
                    return;
                }

                String from = Parsing.parseRange(oaiRequest.getFrom(), "from");
                String until = Parsing.parseRange(oaiRequest.getUntil(), "until");

                q.add(String.format("%s:[%s TO %s]", Parameters.getParam("field_index_datestamp"), from, until));

                if (Validation.isValidSet(oaiRequest.getSet(), response))
                    addSetToQuery(oaiRequest.getSet(), q);
                else
                    return;

                int cursor = oaiRequest.getCursor();
                int nextCursor = cursor + len;
                docList = runQuery(request, q, cursor, len);

                if (!Validation.hasMatchingRecords(response, docList.size()))
                    return;

                final ResumptionTokenType rt = (docList.matches() > nextCursor)
                        ? ResumptionToken.encodeResumptionToken(oaiRequest, cursor, nextCursor, docList.matches(), (Integer) Parameters.getParam("resumptionTokenExpirationInSeconds"))
                        : null;

                if (verb == VerbType.LIST_RECORDS)
                    oai.setListRecords(listRecords(rt));
                else
                    oai.setListIdentifiers(listIdentifiers(rt));
                break;

            case GET_RECORD:
                if (!Validation.isValidIdentifier(response, oaiRequest)) {
                    return;
                }
                if (!Validation.isValidMetadataPrefix(response, oaiRequest)) {
                    return;
                }
                addToQuery(String.format("%s:\"%s\"", Parameters.getParam("field_index_identifier"), Parsing.stripOaiPrefix(oaiRequest.getIdentifier())), q);
                docList = runQuery(request, q, 0, 1);
                oai.setGetRecord(getRecord(response, docList));
                break;
        }

        response.add("docList", docList);
    }

    private void addToQuery(String query, List<String> q) {
        q.add(query);
    }

    /**
     * addSetToQuery
     *
     * @param setParam The index or query template string
     * @param q        The query list
     */
    private void addSetToQuery(String setParam, List<String> q) {

        if (setParam != null) {
            final String field_index_set = (String) Parameters.getParam("field_index_set");
            if (field_index_set.contains("%s"))
                addToQuery(String.format(field_index_set, setParam), q);
            else
                addToQuery(String.format("%s:\"%s\"", field_index_set, setParam), q);
        }
    }

    private DocList runQuery(SolrQueryRequest request, List<String> q, int cursor, int len) throws IOException, SyntaxError {

        final SortField sortField = new SortField((String) Parameters.getParam("field_sort_datestamp"), SortField.Type.LONG, false);
        final Sort sort = new Sort(sortField);

        String[] queryParts = q.toArray(new String[q.size()]);
        String join = Parsing.join(queryParts, " AND ");
        final String static_query = (String) Parameters.getParam("static_query");
        if (!static_query.isEmpty()) join += " AND (" + static_query + ")";
        final QParser parser = QParser.getParser(join, QParserPlugin.DEFAULT_QTYPE, request);

        Query filter = null;
        if (true == Boolean.parseBoolean((Parameters.getParam("enable_filter_query")).toString())) {
            final String fq = request.getParams().get("fq");
            if (fq != null) {
                filter = QParser.getParser(fq, QParserPlugin.DEFAULT_QTYPE, request).getQuery();
            }
        }

        return request.getSearcher().getDocList(parser.getQuery(), filter, sort, cursor, len);
    }

    private ListIdentifiersType listIdentifiers(ResumptionTokenType token) {
        final ListIdentifiersType records = new ListIdentifiersType();
        records.setResumptionToken(token);
        return records;
    }

    private ListRecordsType listRecords(ResumptionTokenType token) {
        final ListRecordsType records = new ListRecordsType();
        records.setResumptionToken(token);
        return records;
    }

    private GetRecordType getRecord(SolrQueryResponse response, DocList docList) {
        if (!Validation.isAvailableIdentifier(response, docList.size()))
            return null;
        if (!Validation.hasMatchingRecords(response, docList.size()))
            return null;
        return new GetRecordType();
    }

    @Override
    public void init(NamedList args) {
        super.init(args);
        Parameters.setParam(args, "wt", "oai");
        Parameters.setParam(args, "proxyurl", "");
        Parameters.setParam(args, "maxrecords", 200);
        Parameters.setParam(args, "resumptionTokenExpirationInSeconds", 86400);
        Parameters.setParam(args, "separator", ",");
        Parameters.setParam(args, "field_index_identifier", "id");
        Parameters.setParam(args, "prefix", "");
        Parameters.setParam(args, "field_index_datestamp", "datestamp");
        Parameters.setParam(args, "field_sort_datestamp", "datestamp");
        Parameters.setParam(args, "field_index_set", "set");
        Parameters.setParam(args, "static_query", "");
        Parameters.setParam(args, "enable_filter_query", false);
        Parameters.setParam(args, "xslt_parameters", new NamedList<List>());

        final File file = getOaiHome(args);
        if (!file.exists()) {
            log.fatal("Could not locate the oai_directory. Set the oai_home property in the solrconfig.xml's OAIRequestHandler section");
            return;
        }

        final List maxrecords = args.getAll("maxrecords");
        if (maxrecords == null)
            Parameters.setParam(args, "maxrecords_default", 200);
        else {
            SolrParams p = SolrParams.toSolrParams((NamedList) maxrecords.get(0));
            final Iterator<String> iterator = p.getParameterNamesIterator();
            while (iterator.hasNext()) {
                String key = iterator.next();
                Parameters.setParam(args, "maxrecords_" + key, p.getInt(key));
            }
        }

        // Add our marshallers
        try {
            final JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class);
            Parameters.setParam("marshaller", jc.createMarshaller());
            Parameters.setParam("unmarshaller", jc.createUnmarshaller());
        } catch (JAXBException e) {
            log.error(e);
        }

        try {
            Parameters.setParam(VerbType.IDENTIFY, Parsing.loadStaticVerb(VerbType.IDENTIFY));
        } catch (FileNotFoundException e) {
            log.warn(e);
        } catch (JAXBException e) {
            log.error(e);
        }

        try {
            Parameters.setParam(VerbType.LIST_METADATA_FORMATS, Parsing.loadStaticVerb(VerbType.LIST_METADATA_FORMATS));
        } catch (FileNotFoundException e) {
            log.warn(e);
        } catch (JAXBException e) {
            log.error(e);
        }

        try {
            Parameters.setParam(VerbType.LIST_SETS, Parsing.loadStaticVerb(VerbType.LIST_SETS));
        } catch (FileNotFoundException e) {
            log.warn(e);
        } catch (JAXBException e) {
            log.error(e);
        }

        addStylesheets(file);
    }

    private File getOaiHome(NamedList args) {
        String solr_home = SolrResourceLoader.locateSolrHome();
        if (solr_home == null)
            solr_home = System.getProperty("solr.solr.home");
        if ("".equals(solr_home))
            solr_home = "/opt/solr/server/solr";
        String oai_home = (String) args.get("oai_home");
        oai_home = (oai_home == null)
                ? solr_home + File.separatorChar + "oai"
                : solr_home + oai_home;
        File file = new File(oai_home);
        log.info("oai_home=" + oai_home);
        args.remove("oai_home");
        Parameters.setParam(args, "oai_home", oai_home);
        return file;
    }

    private void addStylesheets(File oai_home) {

        for (File file : oai_home.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                String name = file.getName().toLowerCase();
                return !name.equals("oai.xsl") && (name.endsWith(".xsl") || name.endsWith(".xslt"));
            }
        })) {

            final TransformerFactory tf = TransformerFactory.newInstance();
            try {
                final Source xslSource = new StreamSource(file);
                xslSource.setSystemId(file.toURI().toURL().toString());
                final Templates templates = tf.newTemplates(xslSource);
                String metadataPrefix = FilenameUtils.removeExtension(file.getName());
                Parameters.setParam(metadataPrefix, templates);
            } catch (TransformerConfigurationException e) {
                log.error(e);
            } catch (MalformedURLException e) {
                log.error(e);
            }
        }
    }

    @Override
    public String getDescription() {
        return "An OAI2 request handler";
    }

    @Override
    public String getSource() {
        return "$URL: https://github.com/IISH/oai4solr $";
    }

    @Override
    public String getVersion() {
        return "$4.x-1.0 $";
    }
}