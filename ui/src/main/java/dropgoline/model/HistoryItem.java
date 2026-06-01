package dropgoline.model;

public record HistoryItem(String timestamp, String content,boolean isIncoming, String type){

    public String getTimestamp() {return timestamp;}
    public String getContent() {return content;}
    public boolean isIncoming() {return isIncoming;}
    public String getType() {return type;}
}
