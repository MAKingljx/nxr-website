-- A configurable menu permission for staff who manage all cards without order or finance access.
-- Grant this function to a role in Role Management; this migration grants it to no role.
INSERT INTO sys_menu (
    menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark
)
SELECT 2160,'全局卡片管理',2000,90,'#','','','',1,0,
       'F','0','0','nxr:card:global','#','admin',CURRENT_TIMESTAMP,'',NULL,
       '授权后可跨业务线管理卡片；不授予订单、客户财务或系统管理权限'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE menu_id=2160 OR perms='nxr:card:global'
);
