package app.pooi.redissearch.search;

import app.pooi.redissearch.configuration.RedisSearchConfiguration;
import app.pooi.redissearch.search.anno.CreateIndex;
import app.pooi.redissearch.search.anno.Field;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.hash.Jackson2HashMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static app.pooi.redissearch.search.SearchCore.Util.*;

@Slf4j
@RestController
@Service
public class SearchCore {

    private StringRedisTemplate redisTemplate;
    private RedisSearchConfiguration redisSearchConfiguration;
    private Jackson2HashMapper hashMapper = new Jackson2HashMapper(true);

    @Data
    private static class Person {
        private Long id;
        private String name;
        private Integer age;
        private Long ctime;
    }

    @PostMapping("/person")
    @CreateIndex(index = "person", documentId = "#p0.id", fields = { @Field(propertyName = "name", value = "#p0.name"),
            @Field(propertyName = "age", value = "#p0.age", sort = true),
            @Field(propertyName = "ctime", value = "#p0.ctime", sort = true) })
    Person addPerson(Person person) {
        return person;
    }

    public SearchCore(StringRedisTemplate redisTemplate, RedisSearchConfiguration redisSearchConfiguration) {
        this.redisTemplate = redisTemplate;
        this.redisSearchConfiguration = redisSearchConfiguration;
    }

    /**
     * 
     * write index field met infos
     * 
     * @param index indexName
     * @param fieldMeta field meta info map
     */
    public void indexMeta(String index, Map<String, FieldMeta> fieldMeta) {
        String idxMetaName = genIdxMetaName(this.redisSearchConfiguration.getPrefix(), index);
        log.info("writing {} meta info...", idxMetaName);
        this.redisTemplate.opsForHash().putAll(idxMetaName,
                hashMapper.toHash(fieldMeta));
    }

    @PostMapping("/index")
    public int indexDocument(final String index, final String field, final String documentId, final String document) {
        return this.indexDocument(index, field, documentId, document, doc -> Lists.newArrayList(doc.split("")));
    }

    public int indexDocument(final String index, final String field, final String documentId, final String document,
            final Function<String, List<String>> tokenizer) {

        final List<String> tokens = tokenizer != null ? tokenizer.apply(document) : Collections.singletonList(document);

        final String docKey = genDocIdxName(this.redisSearchConfiguration.getPrefix(), index, documentId);

        final List<Object> results = redisTemplate.executePipelined(new SessionCallback<Integer>() {
            @Override
            public Integer execute(RedisOperations operations) throws DataAccessException {
                final StringRedisTemplate template = (StringRedisTemplate) operations;

                final String[] idxs = tokens.stream()
                        .map(word -> genIdxName(redisSearchConfiguration.getPrefix(), index, field, word))
                        .peek(idx -> ((StringRedisTemplate) operations).opsForSet().add(idx, documentId))
                        .toArray(String[]::new);

                template.opsForSet().add(docKey, idxs);
                return null;
            }
        });
        return results.size();
    }

    public int indexSortField(final String index, final String field, final String documentId, final Double document) {

        final String docKey = genDocIdxName(this.redisSearchConfiguration.getPrefix(), index, documentId);

        final List<Object> results = redisTemplate.executePipelined(new SessionCallback<Integer>() {
            @Override
            public Integer execute(RedisOperations operations) throws DataAccessException {
                final StringRedisTemplate template = (StringRedisTemplate) operations;
                final String idxName = genSortIdxName(redisSearchConfiguration.getPrefix(), index, field);
                template.opsForZSet().add(idxName, documentId, document);
                template.opsForSet().add(docKey, idxName);
                return null;
            }
        });
        return results.size();
    }

    @DeleteMapping("/index")
    public int deleteDocumentIndex(final String index, final String documentId) {
        final String docKey = genDocIdxName(this.redisSearchConfiguration.getPrefix(), index, documentId);
        final Boolean hasKey = redisTemplate.hasKey(docKey);
        if (!hasKey) {
            return 0;
        }

        final List<Object> results = redisTemplate.executePipelined(new SessionCallback<Integer>() {
            @Override
            public Integer execute(RedisOperations operations) throws DataAccessException {
                final Set<String> idx = redisTemplate.opsForSet().members(docKey);
                ((StringRedisTemplate) operations).delete(idx);
                ((StringRedisTemplate) operations).delete(docKey);
                return null;
            }
        });
        return results.size();
    }

    @PatchMapping("/index")
    public int updateDocumentIndex(final String index, final String field, final String documentId,
            final String document) {
        this.deleteDocumentIndex(index, documentId);
        return this.indexDocument(index, field, documentId, document);
    }

    public int updateSortField(final String index, final String field, final String documentId, final Double document) {
        this.deleteDocumentIndex(index, documentId);
        return this.indexSortField(index, field, documentId, document);
    }

    private Consumer<SetOperations<String, String>> operateAndStore(String method, String key, Collection<String> keys,
            String destKey) {
        switch (method) {
        case "intersectAndStore":
            return (so) -> so.intersectAndStore(key, keys, destKey);
        case "unionAndStore":
            return (so) -> so.unionAndStore(key, keys, destKey);
        case "differenceAndStore":
            return (so) -> so.differenceAndStore(key, keys, destKey);
        default:
            return so -> {
            };
        }
    }

    private Consumer<ZSetOperations<String, String>> zOperateAndStore(String method, String key,
            Collection<String> keys, String destKey, final RedisZSetCommands.Weights weights) {
        switch (method) {
        case "intersectAndStore":
            return (so) -> so.intersectAndStore(key, keys, destKey, RedisZSetCommands.Aggregate.SUM, weights);
        case "unionAndStore":
            return (so) -> so.unionAndStore(key, keys, destKey, RedisZSetCommands.Aggregate.SUM, weights);
        default:
            return so -> {
            };
        }
    }

    private String common(String index, String method, List<String> keys, long ttl) {
        final String destKey = Util.genQueryIdxName(this.redisSearchConfiguration.getPrefix(), index);

        redisTemplate.executePipelined(new SessionCallback<String>() {
            @Override
            public <K, V> String execute(RedisOperations<K, V> operations) throws DataAccessException {
                operateAndStore(method, keys.stream().limit(1L).findFirst().get(),
                        keys.stream().skip(1L).collect(Collectors.toList()), destKey)
                                .accept(((StringRedisTemplate) operations).opsForSet());
                ((StringRedisTemplate) operations).expire(destKey, ttl, TimeUnit.SECONDS);
                return null;
            }
        });
        return destKey;
    }

    public String intersect(String index, List<String> keys, long ttl) {
        return common(index, "intersectAndStore", keys, ttl);
    }

    public String union(String index, List<String> keys, long ttl) {
        return common(index, "unionAndStore", keys, ttl);
    }

    public String diff(String index, List<String> keys, long ttl) {
        return common(index, "differenceAndStore", keys, ttl);
    }

    private static Tuple2<Set<Tuple2<String, String>>, Set<Tuple2<String, String>>> parse(String query) {

        final Pattern pattern = Pattern.compile("[+-]?([\\w\\d]+):(\\S+)");

        final Matcher matcher = pattern.matcher(query);

        Set<Tuple2<String, String>> unwant = Sets.newHashSet();
        Set<Tuple2<String, String>> want = Sets.newHashSet();

        while (matcher.find()) {
            String word = matcher.group();

            String prefix = null;
            if (word.length() > 1) {
                prefix = word.substring(0, 1);
            }

            final Tuple2<String, String> t = Tuples.of(matcher.group(1), matcher.group(2));
            if ("-".equals(prefix)) {
                unwant.add(t);
            } else {
                want.add(t);
            }
        }
        return Tuples.of(want, unwant);
    }

    public String query(String index, String query) {

        final Tuple2<Set<Tuple2<String, String>>, Set<Tuple2<String, String>>> parseResult = parse(query);
        final Set<Tuple2<String, String>> want = parseResult.getT1();
        final Set<Tuple2<String, String>> unwant = parseResult.getT2();

        if (want.isEmpty()) {
            return "";
        }

        final Map<String, FieldMeta> entries = (Map<String, FieldMeta>) hashMapper
                .fromHash(redisTemplate.<String, Object>opsForHash()
                        .entries(genIdxMetaName(this.redisSearchConfiguration.getPrefix(), index)));

        // union
        final List<Tuple2<String, String>> unionFields = want.stream().filter(w -> w.getT2().contains(","))
                .filter(w -> "true".equals(entries.get(w.getT1()).getSort())).collect(Collectors.toList());
        final List<String> unionIdx = unionFields.stream()
                .flatMap(w -> Arrays.stream(w.getT2().split(",")).map(value -> Tuples.of(w.getT1(), value)))
                .map(w -> genIdxName(this.redisSearchConfiguration.getPrefix(), index, w.getT1(), w.getT2()))
                .collect(Collectors.toList());

        final String unionResultId = unionIdx.isEmpty() ? "" : this.union(index, unionIdx, 30L);

        want.removeAll(unionFields);

        // intersect
        final List<String> intersectIdx = want.stream().flatMap(t -> {
            if ("true".equals(entries.get(t.getT1()).getSort()))
                return Stream.of(t);
            return Arrays.stream(t.getT2().split("")).map(value -> Tuples.of(t.getT1(), value));
        }).map(w -> genIdxName(this.redisSearchConfiguration.getPrefix(), index, w.getT1(), w.getT2()))
                .collect(Collectors.toList());

        if (!unionResultId.isEmpty())
            intersectIdx.add(unionResultId);

        String intersectResult = this.intersect(index, intersectIdx, 30L);

        // diff
        return unwant
                .isEmpty()
                        ? intersectResult
                        : this.diff(
                                index, Stream
                                        .concat(Stream.of(intersectResult),
                                                unwant.stream()
                                                        .map(w -> genIdxName(this.redisSearchConfiguration.getPrefix(),
                                                                index, w.getT1(), w.getT2())))
                                        .collect(Collectors.toList()),
                                30L);
    }

    @GetMapping("/query/{index}")
    public Set<String> queryAndSort(@PathVariable("index") String index, @RequestParam("param") String query,
            @RequestParam("sort") String sort, Integer start, Integer stop) {
        final String[] sorts = sort.split(" ");

        final Map<String, Integer> map = Arrays.stream(sorts).collect(Collectors.toMap(f -> {
            if (f.startsWith("+") || f.startsWith("-")) {
                f = f.substring(1);
            }
            return genSortIdxName(this.redisSearchConfiguration.getPrefix(), "person", f);
        }, field -> field.startsWith("-") ? -1 : 1));

        final int[] weights = map.values().stream().mapToInt(Integer::intValue).toArray();

        // if (!sort.startsWith("+") && !sort.startsWith("-")) {
        // sort = "+" + sort;
        // }
        // boolean desc = sort.startsWith("-");
        // sort = sort.substring(1);

        String queryId = this.query(index, query);
        Long size;
        if (queryId.length() == 0 || (size = redisTemplate.opsForSet().size(queryId)) == null || size == 0) {
            return Collections.emptySet();
        }

        final String resultId = genQueryIdxName(this.redisSearchConfiguration.getPrefix(), index);

        // String sortField = sort;

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                final StringRedisTemplate template = (StringRedisTemplate) operations;

                // template.opsForZSet().intersectAndStore(genSortIdxName(index, sortField),
                // queryId, resultId);

                SearchCore.this.zOperateAndStore("intersectAndStore", map.keySet().stream().limit(1L).findFirst().get(),
                        Stream.concat(map.keySet().stream().skip(1L), Stream.of(queryId)).collect(Collectors.toList()),
                        resultId, RedisZSetCommands.Weights.of(ArrayUtils.add(weights, 0)))
                        .accept(template.opsForZSet());

                // template.opsForZSet().size(resultId);
                template.expire(resultId, 30L, TimeUnit.SECONDS);

                return null;
            }
        });

        // sort
        return redisTemplate.opsForZSet().range(resultId, start, stop);

    }

    static class Util {

        private Util() {
        }

        static String genIdxMetaName(String prefix, String index) {
            return String.format("rs:%s:meta:idx:%s", prefix, index);
        }

        static String genIdxName(String prefix, String index, String field, String value) {
            return String.format("rs:%s:idx:%s:%s:%s", prefix, index, field, value);
        }

        static String genSortIdxName(String prefix, String index, String field) {
            return String.format("rs:%s:idx:%s:%s", prefix, index, field);
        }

        static String genQueryIdxName(String prefix, String index) {
            return String.format("rs:%s:idx:%s:q:%s", prefix, index, UUID.randomUUID().toString());
        }

        static String genDocIdxName(String prefix, String index, String documentId) {
            return String.format("rs:%s:doc:%s:%s", prefix, index, documentId);
        }
    }
}
