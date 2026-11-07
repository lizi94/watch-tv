package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.RoomWarnings;

import com.fongmi.android.tv.bean.Config;

import java.util.List;

@Dao
public abstract class ConfigDao extends BaseDao<Config> {

    @Query("SELECT * FROM Config")
    public abstract List<Config> findAll();

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    public abstract List<Config> findByType(int type);

    /**
     * 推送清单用的查询：只返回**启用**的配置。
     *
     * <p>停用的配置不再推给手机端（软删除语义：还在历史记录里，但不再参与同步）。</p>
     *
     * <p>⚠ 唯一调用方是 {@code Config.findUrls()}（→ Action/SyncDialog 的推送），
     * 「当前使用中」判定走 {@code findOne}，不受这里过滤影响。</p>
     */
    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH)
    @Query("SELECT id, name, url, type, time, checked FROM Config WHERE type = :type AND checked = 1 ORDER BY time DESC")
    public abstract List<Config> findUrlByType(int type);

    @Query("SELECT * FROM Config WHERE id = :id")
    public abstract Config findById(int id);

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC LIMIT 1")
    public abstract Config findOne(int type);

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    public abstract Config find(String url, int type);

    @Query("DELETE FROM Config WHERE url = :url AND type = :type")
    public abstract void delete(String url, int type);

    @Query("DELETE FROM Config WHERE url = :url")
    public abstract void delete(String url);
}
