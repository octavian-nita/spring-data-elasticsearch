/*
 * Copyright 2021-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.elasticsearch.client.elc;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.transport.Version;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.data.elasticsearch.client.UnsupportedBackendOperation;
import org.springframework.data.elasticsearch.core.AbstractElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.IndexedObjectInformation;
import org.springframework.data.elasticsearch.core.MultiGetItem;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchScrollHits;
import org.springframework.data.elasticsearch.core.cluster.ClusterOperations;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.document.SearchDocumentResponse;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.BulkOptions;
import org.springframework.data.elasticsearch.core.query.ByQueryResponse;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.MoreLikeThisQuery;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import org.springframework.data.elasticsearch.core.reindex.ReindexRequest;
import org.springframework.data.elasticsearch.core.reindex.ReindexResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Implementation of {@link org.springframework.data.elasticsearch.core.ElasticsearchOperations} using the new
 * Elasticsearch client.
 *
 * @author Peter-Josef Meisch
 * @since 4.4
 */
public class ElasticsearchTemplate extends AbstractElasticsearchTemplate {

	private final ElasticsearchClient client;
	private final RequestConverter requestConverter;
	private final ResponseConverter responseConverter;
	private final JsonpMapper jsonpMapper;
	private final ElasticsearchExceptionTranslator exceptionTranslator;

	// region _initialization
	public ElasticsearchTemplate(ElasticsearchClient client, ElasticsearchConverter elasticsearchConverter) {
		super(elasticsearchConverter);

		Assert.notNull(client, "client must not be null");

		this.client = client;
		this.jsonpMapper = client._transport().jsonpMapper();
		requestConverter = new RequestConverter(elasticsearchConverter, jsonpMapper);
		responseConverter = new ResponseConverter(jsonpMapper);
		exceptionTranslator = new ElasticsearchExceptionTranslator(jsonpMapper);
	}

	@Override
	protected AbstractElasticsearchTemplate doCopy() {
		return new ElasticsearchTemplate(client, elasticsearchConverter);
	}
	// endregion

	// region child templates
	@Override
	public IndexOperations indexOps(Class<?> clazz) {
		return new IndicesTemplate(client.indices(), elasticsearchConverter, clazz);
	}

	@Override
	public IndexOperations indexOps(IndexCoordinates index) {
		return new IndicesTemplate(client.indices(), elasticsearchConverter, index);
	}

	@Override
	public ClusterOperations cluster() {
		return new ClusterTemplate(client.cluster(), elasticsearchConverter);
	}
	// endregion

	// region document operations
	@Override
	@Nullable
	public <T> T get(String id, Class<T> clazz, IndexCoordinates index) {

		GetRequest getRequest = requestConverter.documentGetRequest(elasticsearchConverter.convertId(id),
				routingResolver.getRouting(), index, false);
		GetResponse<EntityAsMap> getResponse = execute(client -> client.get(getRequest, EntityAsMap.class));

		ReadDocumentCallback<T> callback = new ReadDocumentCallback<>(elasticsearchConverter, clazz, index);
		return callback.doWith(DocumentAdapters.from(getResponse));
	}

	@Override
	public <T> List<MultiGetItem<T>> multiGet(Query query, Class<T> clazz, IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(clazz, "clazz must not be null");

		MgetRequest request = requestConverter.documentMgetRequest(query, clazz, index);
		MgetResponse<EntityAsMap> result = execute(client -> client.mget(request, EntityAsMap.class));

		ReadDocumentCallback<T> callback = new ReadDocumentCallback<>(elasticsearchConverter, clazz, index);

		return DocumentAdapters.from(result).stream() //
				.map(multiGetItem -> MultiGetItem.of( //
						multiGetItem.isFailed() ? null : callback.doWith(multiGetItem.getItem()), multiGetItem.getFailure())) //
				.collect(Collectors.toList());
	}

	@Override
	public void bulkUpdate(List<UpdateQuery> queries, BulkOptions bulkOptions, IndexCoordinates index) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public ByQueryResponse delete(Query query, Class<?> clazz, IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");

		DeleteByQueryRequest request = requestConverter.documentDeleteByQueryRequest(query, clazz, index,
				getRefreshPolicy());

		DeleteByQueryResponse response = execute(client -> client.deleteByQuery(request));

		return responseConverter.byQueryResponse(response);
	}

	@Override
	public UpdateResponse update(UpdateQuery updateQuery, IndexCoordinates index) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public ByQueryResponse updateByQuery(UpdateQuery updateQuery, IndexCoordinates index) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public String doIndex(IndexQuery query, IndexCoordinates indexCoordinates) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(indexCoordinates, "indexCoordinates must not be null");

		IndexRequest<?> indexRequest = requestConverter.documentIndexRequest(query, indexCoordinates, refreshPolicy);

		IndexResponse indexResponse = execute(client -> client.index(indexRequest));

		Object queryObject = query.getObject();

		if (queryObject != null) {
			query.setObject(updateIndexedObject(queryObject, IndexedObjectInformation.of(indexResponse.id(),
					indexResponse.seqNo(), indexResponse.primaryTerm(), indexResponse.version())));
		}

		return indexResponse.id();
	}

	@Override
	protected boolean doExists(String id, IndexCoordinates index) {

		Assert.notNull(id, "id must not be null");
		Assert.notNull(index, "index must not be null");

		GetRequest request = requestConverter.documentGetRequest(id, routingResolver.getRouting(), index, true);

		return execute(client -> client.get(request, EntityAsMap.class)).found();
	}

	@Override
	protected String doDelete(String id, @Nullable String routing, IndexCoordinates index) {

		Assert.notNull(id, "id must not be null");
		Assert.notNull(index, "index must not be null");

		DeleteRequest request = requestConverter.documentDeleteRequest(elasticsearchConverter.convertId(id), routing, index,
				getRefreshPolicy());
		return execute(client -> client.delete(request)).id();
	}

	@Override
	public ReindexResponse reindex(ReindexRequest reindexRequest) {

		Assert.notNull(reindexRequest, "reindexRequest must not be null");

		co.elastic.clients.elasticsearch.core.ReindexRequest reindexRequestES = requestConverter.reindex(reindexRequest,
				true);
		co.elastic.clients.elasticsearch.core.ReindexResponse reindexResponse = execute(
				client -> client.reindex(reindexRequestES));
		return responseConverter.reindexResponse(reindexResponse);
	}

	@Override
	public String submitReindex(ReindexRequest reindexRequest) {

		co.elastic.clients.elasticsearch.core.ReindexRequest reindexRequestES = requestConverter.reindex(reindexRequest,
				false);
		co.elastic.clients.elasticsearch.core.ReindexResponse reindexResponse = execute(
				client -> client.reindex(reindexRequestES));

		if (reindexResponse.task() == null) {
			// todo #1973 check behaviour and create issue in ES if necessary
			throw new UnsupportedBackendOperation("ElasticsearchClient did not return a task id on submit request");
		}

		return reindexResponse.task();
	}

	@Override
	public List<IndexedObjectInformation> doBulkOperation(List<?> queries, BulkOptions bulkOptions,
			IndexCoordinates index) {

		BulkRequest bulkRequest = requestConverter.documentBulkRequest(queries, bulkOptions, index, refreshPolicy);
		BulkResponse bulkResponse = execute(client -> client.bulk(bulkRequest));
		List<IndexedObjectInformation> indexedObjectInformationList = checkForBulkOperationFailure(bulkResponse);
		updateIndexedObjectsWithQueries(queries, indexedObjectInformationList);
		return indexedObjectInformationList;
	}

	// endregion

	@Override
	protected String getClusterVersion() {
		return execute(client -> client.info().version().number());

	}

	@Override
	protected String getVendor() {
		return "Elasticsearch";
	}

	@Override
	protected String getRuntimeLibraryVersion() {
		return Version.VERSION.toString();
	}

	// region search operations
	@Override
	public long count(Query query, @Nullable Class<?> clazz, IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(index, "index must not be null");

		SearchRequest searchRequest = requestConverter.searchRequest(query, clazz, index, true, false);

		SearchResponse<EntityAsMap> searchResponse = execute(client -> client.search(searchRequest, EntityAsMap.class));

		return searchResponse.hits().total().value();
	}

	@Override
	public <T> SearchHits<T> search(Query query, Class<T> clazz, IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(index, "index must not be null");

		SearchRequest searchRequest = requestConverter.searchRequest(query, clazz, index, false, false);
		SearchResponse<EntityAsMap> searchResponse = execute(client -> client.search(searchRequest, EntityAsMap.class));

		ReadDocumentCallback<T> readDocumentCallback = new ReadDocumentCallback<>(elasticsearchConverter, clazz, index);
		SearchDocumentResponse.EntityCreator<T> entityCreator = getEntityCreator(readDocumentCallback);
		SearchDocumentResponseCallback<SearchHits<T>> callback = new ReadSearchDocumentResponseCallback<>(clazz, index);

		return callback.doWith(SearchDocumentResponseBuilder.from(searchResponse, entityCreator, jsonpMapper));
	}

	@Override
	protected <T> SearchHits<T> doSearch(MoreLikeThisQuery query, Class<T> clazz, IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(clazz, "clazz must not be null");
		Assert.notNull(index, "index must not be null");

		return search(NativeQuery.builder() //
				.withQuery(q -> q.moreLikeThis(requestConverter.moreLikeThisQuery(query, index)))//
				.withPageable(query.getPageable()) //
				.build(), clazz, index);
	}

	@Override
	protected <T> SearchScrollHits<T> searchScrollStart(long scrollTimeInMillis, Query query, Class<T> clazz,
			IndexCoordinates index) {

		Assert.notNull(query, "query must not be null");
		Assert.notNull(query.getPageable(), "pageable of query must not be null.");

		SearchRequest request = requestConverter.searchRequest(query, clazz, index, false, scrollTimeInMillis);
		SearchResponse<EntityAsMap> response = execute(client -> client.search(request, EntityAsMap.class));

		return getSearchScrollHits(clazz, index, response);
	}

	@Override
	protected <T> SearchScrollHits<T> searchScrollContinue(String scrollId, long scrollTimeInMillis, Class<T> clazz,
			IndexCoordinates index) {

		Assert.notNull(scrollId, "scrollId must not be null");

		ScrollRequest request = ScrollRequest
				.of(sr -> sr.scrollId(scrollId).scroll(Time.of(t -> t.time(scrollTimeInMillis + "ms"))));
		ScrollResponse<EntityAsMap> response = execute(client -> client.scroll(request, EntityAsMap.class));

		return getSearchScrollHits(clazz, index, response);
	}

	private <T, R extends SearchResponse<EntityAsMap>> SearchScrollHits<T> getSearchScrollHits(Class<T> clazz,
			IndexCoordinates index, R response) {
		ReadDocumentCallback<T> documentCallback = new ReadDocumentCallback<>(elasticsearchConverter, clazz, index);
		SearchDocumentResponseCallback<SearchScrollHits<T>> callback = new ReadSearchScrollDocumentResponseCallback<>(clazz,
				index);

		return callback
				.doWith(SearchDocumentResponseBuilder.from(response, getEntityCreator(documentCallback), jsonpMapper));
	}

	@Override
	protected void searchScrollClear(List<String> scrollIds) {

		Assert.notNull(scrollIds, "scrollIds must not be null");

		if (!scrollIds.isEmpty()) {
			ClearScrollRequest request = ClearScrollRequest.of(csr -> csr.scrollId(scrollIds));
			execute(client -> client.clearScroll(request));
		}
	}

	@Override
	public <T> List<SearchHits<T>> multiSearch(List<? extends Query> queries, Class<T> clazz, IndexCoordinates index) {

		Assert.notNull(queries, "queries must not be null");
		Assert.notNull(clazz, "clazz must not be null");

		List<MultiSearchQueryParameter> multiSearchQueryParameters = new ArrayList<>(queries.size());
		for (Query query : queries) {
			multiSearchQueryParameters.add(new MultiSearchQueryParameter(query, clazz, getIndexCoordinatesFor(clazz)));
		}

		// noinspection unchecked
		return doMultiSearch(multiSearchQueryParameters).stream().map(searchHits -> (SearchHits<T>) searchHits)
				.collect(Collectors.toList());
	}

	@Override
	public List<SearchHits<?>> multiSearch(List<? extends Query> queries, List<Class<?>> classes) {

		Assert.notNull(queries, "queries must not be null");
		Assert.notNull(classes, "classes must not be null");
		Assert.isTrue(queries.size() == classes.size(), "queries and classes must have the same size");

		List<MultiSearchQueryParameter> multiSearchQueryParameters = new ArrayList<>(queries.size());
		Iterator<Class<?>> it = classes.iterator();
		for (Query query : queries) {
			Class<?> clazz = it.next();
			multiSearchQueryParameters.add(new MultiSearchQueryParameter(query, clazz, getIndexCoordinatesFor(clazz)));
		}

		return doMultiSearch(multiSearchQueryParameters);
	}

	@Override
	public List<SearchHits<?>> multiSearch(List<? extends Query> queries, List<Class<?>> classes,
			IndexCoordinates index) {

		Assert.notNull(queries, "queries must not be null");
		Assert.notNull(classes, "classes must not be null");
		Assert.notNull(index, "index must not be null");
		Assert.isTrue(queries.size() == classes.size(), "queries and classes must have the same size");

		List<MultiSearchQueryParameter> multiSearchQueryParameters = new ArrayList<>(queries.size());
		Iterator<Class<?>> it = classes.iterator();
		for (Query query : queries) {
			Class<?> clazz = it.next();
			multiSearchQueryParameters.add(new MultiSearchQueryParameter(query, clazz, index));
		}

		return doMultiSearch(multiSearchQueryParameters);
	}

	private List<SearchHits<?>> doMultiSearch(List<MultiSearchQueryParameter> multiSearchQueryParameters) {
		throw new UnsupportedOperationException("not implemented");
	}

	/**
	 * value class combining the information needed for a single query in a multisearch request.
	 */
	private static class MultiSearchQueryParameter {
		final Query query;
		final Class<?> clazz;
		final IndexCoordinates index;

		public MultiSearchQueryParameter(Query query, Class<?> clazz, IndexCoordinates index) {
			this.query = query;
			this.clazz = clazz;
			this.index = index;
		}
	}
	// endregion

	// region client callback
	/**
	 * Callback interface to be used with {@link #execute(ElasticsearchTemplate.ClientCallback)} for operating directly on
	 * the {@link ElasticsearchClient}.
	 */
	@FunctionalInterface
	public interface ClientCallback<T> {
		T doWithClient(ElasticsearchClient client) throws IOException;
	}

	/**
	 * Execute a callback with the {@link ElasticsearchClient} and provide exception translation.
	 *
	 * @param callback the callback to execute, must not be {@literal null}
	 * @param <T> the type returned from the callback
	 * @return the callback result
	 */
	public <T> T execute(ElasticsearchTemplate.ClientCallback<T> callback) {

		Assert.notNull(callback, "callback must not be null");

		try {
			return callback.doWithClient(client);
		} catch (IOException | RuntimeException e) {
			throw exceptionTranslator.translateException(e);
		}
	}
	// endregion

	// region helper methods
	@Override
	public Query matchAllQuery() {
		return NativeQuery.builder().withQuery(qb -> qb.matchAll(mab -> mab)).build();
	}

	@Override
	public Query idsQuery(List<String> ids) {
		return NativeQuery.builder().withQuery(qb -> qb.ids(iq -> iq.values(ids))).build();
	}

	/**
	 * extract the list of {@link IndexedObjectInformation} from a {@link BulkResponse}.
	 *
	 * @param bulkResponse the response to evaluate
	 * @return the list of the {@link IndexedObjectInformation}s
	 */
	protected List<IndexedObjectInformation> checkForBulkOperationFailure(BulkResponse bulkResponse) {

		if (bulkResponse.errors()) {
			Map<String, String> failedDocuments = new HashMap<>();
			for (BulkResponseItem item : bulkResponse.items()) {

				if (item.error() != null) {
					failedDocuments.put(item.id(), item.error().reason());
				}
			}
			throw new BulkFailureException(
					"Bulk operation has failures. Use ElasticsearchException.getFailedDocuments() for detailed messages ["
							+ failedDocuments + ']',
					failedDocuments);
		}

		return bulkResponse.items().stream()
				.map(item -> IndexedObjectInformation.of(item.id(), item.seqNo(), item.primaryTerm(), item.version()))
				.collect(Collectors.toList());

	}
	// endregion

}
