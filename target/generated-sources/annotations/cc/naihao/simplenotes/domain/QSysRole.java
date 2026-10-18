package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysRole is a Querydsl query type for SysRole
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysRole extends EntityPathBase<SysRole> {

    private static final long serialVersionUID = 443646968L;

    public static final QSysRole sysRole = new QSysRole("sysRole");

    public final QBaseEntity _super = new QBaseEntity(this);

    public final NumberPath<Short> builtin = createNumber("builtin", Short.class);

    public final StringPath code = createString("code");

    //inherited
    public final StringPath createBy = _super.createBy;

    //inherited
    public final DateTimePath<java.time.Instant> createTime = _super.createTime;

    //inherited
    public final StringPath deleteBy = _super.deleteBy;

    //inherited
    public final NumberPath<Short> deleteFlag = _super.deleteFlag;

    //inherited
    public final DateTimePath<java.time.Instant> deleteTime = _super.deleteTime;

    public final StringPath description = createString("description");

    //inherited
    public final StringPath id = _super.id;

    public final StringPath name = createString("name");

    public final NumberPath<Integer> sort = createNumber("sort", Integer.class);

    public final NumberPath<Short> status = createNumber("status", Short.class);

    //inherited
    public final StringPath updateBy = _super.updateBy;

    //inherited
    public final DateTimePath<java.time.Instant> updateTime = _super.updateTime;

    public QSysRole(String variable) {
        super(SysRole.class, forVariable(variable));
    }

    public QSysRole(Path<? extends SysRole> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysRole(PathMetadata metadata) {
        super(SysRole.class, metadata);
    }

}

