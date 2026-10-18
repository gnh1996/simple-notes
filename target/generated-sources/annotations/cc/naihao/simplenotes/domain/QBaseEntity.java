package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QBaseEntity is a Querydsl query type for BaseEntity
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultSupertypeSerializer")
public class QBaseEntity extends EntityPathBase<BaseEntity> {

    private static final long serialVersionUID = -969874721L;

    public static final QBaseEntity baseEntity = new QBaseEntity("baseEntity");

    public final StringPath createBy = createString("createBy");

    public final DateTimePath<java.time.Instant> createTime = createDateTime("createTime", java.time.Instant.class);

    public final StringPath deleteBy = createString("deleteBy");

    public final NumberPath<Short> deleteFlag = createNumber("deleteFlag", Short.class);

    public final DateTimePath<java.time.Instant> deleteTime = createDateTime("deleteTime", java.time.Instant.class);

    public final StringPath id = createString("id");

    public final StringPath updateBy = createString("updateBy");

    public final DateTimePath<java.time.Instant> updateTime = createDateTime("updateTime", java.time.Instant.class);

    public QBaseEntity(String variable) {
        super(BaseEntity.class, forVariable(variable));
    }

    public QBaseEntity(Path<? extends BaseEntity> path) {
        super(path.getType(), path.getMetadata());
    }

    public QBaseEntity(PathMetadata metadata) {
        super(BaseEntity.class, metadata);
    }

}

