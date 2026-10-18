package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysUserRole is a Querydsl query type for SysUserRole
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysUserRole extends EntityPathBase<SysUserRole> {

    private static final long serialVersionUID = -1111001757L;

    public static final QSysUserRole sysUserRole = new QSysUserRole("sysUserRole");

    public final StringPath createBy = createString("createBy");

    public final DateTimePath<java.time.Instant> createTime = createDateTime("createTime", java.time.Instant.class);

    public final StringPath roleId = createString("roleId");

    public final StringPath userId = createString("userId");

    public QSysUserRole(String variable) {
        super(SysUserRole.class, forVariable(variable));
    }

    public QSysUserRole(Path<? extends SysUserRole> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysUserRole(PathMetadata metadata) {
        super(SysUserRole.class, metadata);
    }

}

