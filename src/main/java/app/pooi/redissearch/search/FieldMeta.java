package app.pooi.redissearch.search;

import lombok.Data;


@Data
public class FieldMeta {

    private String sort = "false";

    private String splitFun = "";

    public FieldMeta() {

    }

    public FieldMeta(boolean sort) {
        this.sort = Boolean.toString(sort);
    }
}
