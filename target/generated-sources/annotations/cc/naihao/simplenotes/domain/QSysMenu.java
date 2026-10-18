package cc.naihao.simplenotes.domain;

import static com.querydsl.core.types.PathMetadataFactory.*;

import com.querydsl.core.types.dsl.*;

import com.querydsl.core.types.dsl.StringTemplate;

import com.querydsl.core.types.PathMetadata;
import com.querydsl.core.annotations.Generated;
import com.querydsl.core.types.Path;


/**
 * QSysMenu is a Querydsl query type for SysMenu
 */
@SuppressWarnings("this-escape")
@Generated("com.querydsl.codegen.DefaultEntitySerializer")
public class QSysMenu extends EntityPathBase<SysMenu> {

    private static final long serialVersionUID = 443488481L;

    public static final QSysMenu sysMenu = new QSysMenu("sysMenu");

    public final QBaseEntity _super = new QBaseEntity(this);

    public final EnumPath<ClientType> client = createEnum("client", ClientType.class);

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

    //inherited
    public final StringPath id = _super.id;

    public final StringPath name = createString("name");

    public final StringPath parentId = createString("parentId");

    public final NumberPath<Integer> sort = createNumber("sort", Integer.class);

    public final NumberPath<Short> status = createNumber("status", Short.class);

    public final EnumPath<MenuType> type = createEnum("type", MenuType.class);

    //inherited
    public final StringPath updateBy = _super.updateBy;

    //inherited
    public final DateTimePath<java.time.Instant> updateTime = _super.updateTime;

    public QSysMenu(String variable) {
        super(SysMenu.class, forVariable(variable));
    }

    public QSysMenu(Path<? extends SysMenu> path) {
        super(path.getType(), path.getMetadata());
    }

    public QSysMenu(PathMetadata metadata) {
        super(SysMenu.class, metadata);
    }

}

