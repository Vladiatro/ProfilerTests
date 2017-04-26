package net.falsetrue;

import java.util.ArrayList;
import java.util.List;

public class Test {
    public static void main(String[] args) throws Exception {
        List<Item> items = null;
        int stub = 0;
        int waves = Integer.parseInt(args[0]), itemsCount = Integer.parseInt(args[1]);
        for (int j = 0; j < waves; j++) {
            items = new ArrayList<>();
            for (int i = 0; i < itemsCount; i++) {
                Item item = new Item();
                items.add(item);
                item.a = 3;
                stub = item.a;
            }
        }
        System.out.println(items.size());
    }
}