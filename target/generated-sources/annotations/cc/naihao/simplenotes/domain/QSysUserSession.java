package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysUserSession is a Querydsl query type for SysUserSession
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysUserSession extends EntityPathBase<SysUserSession> {

    private static final long serialVersionUID = -227161463L;

    public static final QSysUserSession sysUserSession = new QSysUserSession("sysUserSession");

    public final EnumPath<ClientType> client = createEnum("client", ClientType.class);

    public final DateTimePath<java.time.Instant> createTime = createDateTime("createTime", java.time.Instant.class);

    public final DateTimePath<java.time.Instant> expireTime = createDateTime("expireTime", java.time.Instant.class);

    public final StringPath id = createString("id");

    public final DateTimePath<java.time.Instant> lastActiveTime = createDateTime("lastActiveTime", java.time.Instant.class);

    public final BooleanPath revoked = createBoolean("revoked");

    public final DateTimePath<java.time.Instant> revokeTime = createDateTime("revokeTime", java.time.Instant.class);

    public final StringPath userId = createString("userId");

    public QSysUserSession(String variable) {
        super(SysUserSession.class, forVariable(variable));
    }

    public QSysUserSession(Path<? extends SysUserSession> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysUserSession(PathMetadata metadata) {
        super(SysUserSession.class, metadata);
    }

}

