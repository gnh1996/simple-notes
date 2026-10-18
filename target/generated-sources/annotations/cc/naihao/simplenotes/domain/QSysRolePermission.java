package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysRolePermission is a Querydsl query type for SysRolePermission
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysRolePermission extends EntityPathBase<SysRolePermission> {

    private static final long serialVersionUID = 506367975L;

    public static final QSysRolePermission sysRolePermission = new QSysRolePermission("sysRolePermission");

    public final StringPath createBy = createString("createBy");

    public final DateTimePath<java.time.Instant> createTime = createDateTime("createTime", java.time.Instant.class);

    public final StringPath menuId = createString("menuId");

    public final StringPath roleId = createString("roleId");

    public QSysRolePermission(String variable) {
        super(SysRolePermission.class, forVariable(variable));
    }

    public QSysRolePermission(Path<? extends SysRolePermission> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysRolePermission(PathMetadata metadata) {
        super(SysRolePermission.class, metadata);
    }

}

