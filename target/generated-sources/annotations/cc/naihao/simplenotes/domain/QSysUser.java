package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysUser is a Querydsl query type for SysUser
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysUser extends EntityPathBase<SysUser> {

    private static final long serialVersionUID = 443739981L;

    public static final QSysUser sysUser = new QSysUser("sysUser");

    public final QBaseEntity _super = new QBaseEntity(this);

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

    public final StringPath email = createString("email");

    //inherited
    public final StringPath id = _super.id;

    public final DateTimePath<java.time.Instant> lastLoginTime = createDateTime("lastLoginTime", java.time.Instant.class);

    public final StringPath nickname = createString("nickname");

    public final StringPath passwordHash = createString("passwordHash");

    public final NumberPath<Short> status = createNumber("status", Short.class);

    //inherited
    public final StringPath updateBy = _super.updateBy;

    //inherited
    public final DateTimePath<java.time.Instant> updateTime = _super.updateTime;

    public final StringPath username = createString("username");

    public QSysUser(String variable) {
        super(SysUser.class, forVariable(variable));
    }

    public QSysUser(Path<? extends SysUser> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysUser(PathMetadata metadata) {
        super(SysUser.class, metadata);
    }

}

