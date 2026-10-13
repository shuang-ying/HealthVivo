"""Build a reproducible Chinese starter catalog from the official USDA SR Legacy CSV archive."""
import csv
import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ARCHIVE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / '.tools/usda-sr-legacy.zip'
SELECTED = [
    (20045, '熟米饭', ['米饭', '白米饭']), (20037, '熟糙米饭', ['糙米饭']),
    (20310, '熟鸡蛋面（含盐）', ['鸡蛋面']), (18064, '小麦面包', ['面包']),
    (1129, '水煮鸡蛋', ['鸡蛋', '煮鸡蛋']), (1123, '生鸡蛋', []),
    (1077, '全脂牛奶', ['牛奶']), (1116, '原味全脂酸奶', ['原味酸奶']),
    (1117, '原味低脂酸奶', ['低脂酸奶']), (5064, '烤鸡胸肉（去皮）', ['熟鸡胸肉']),
    (10061, '烤猪里脊（瘦肉）', ['熟猪里脊']), (23563, '烤牛肉饼（90%瘦肉）', ['牛肉饼']),
    (15237, '熟三文鱼（养殖，干热）', ['熟三文鱼']), (15271, '熟虾', ['虾']),
    (16427, '普通豆腐（未烹调）', ['豆腐']), (16126, '硬豆腐（未烹调）', ['硬豆腐']),
    (9003, '苹果（带皮）', ['苹果']), (9040, '香蕉（去皮可食部）', ['香蕉']),
    (9200, '橙子（可食部）', ['橙子']), (9132, '葡萄', ['葡萄']),
    (9316, '草莓', ['草莓']), (9148, '绿心猕猴桃', ['猕猴桃']),
    (9326, '西瓜（果肉）', ['西瓜']), (9252, '梨（可食部）', ['梨']),
    (9037, '牛油果（果肉）', ['牛油果']), (11090, '生西兰花', ['西兰花']),
    (11529, '生番茄', ['番茄', '西红柿']), (11205, '生黄瓜（带皮）', ['黄瓜']),
    (11124, '生胡萝卜', ['胡萝卜']), (11457, '生菠菜', ['菠菜']),
    (11109, '生卷心菜', ['卷心菜', '包菜']), (11367, '水煮土豆（去皮）', ['熟土豆']),
    (11510, '水煮红薯（去皮）', ['熟红薯']), (11168, '水煮甜玉米（无盐）', ['熟玉米']),
    (11260, '生白蘑菇', ['白蘑菇']), (11451, '水煮毛豆（无盐）', ['熟毛豆']),
    (16057, '水煮鹰嘴豆（无盐）', ['熟鹰嘴豆']), (16070, '水煮扁豆（无盐）', ['熟扁豆']),
    (16087, '生花生仁', ['花生仁']), (12061, '杏仁', ['杏仁']),
    (12023, '芝麻（干）', ['芝麻']), (4053, '橄榄油', ['橄榄油']),
    (4044, '大豆油', ['大豆油']), (19335, '白砂糖', ['白糖']), (19296, '蜂蜜', ['蜂蜜']),
]
SELECTED += [tuple(item) for item in json.loads((ROOT / 'scripts/food-expansion.json').read_text(encoding='utf-8'))]
assert len({item[0] for item in SELECTED}) == len(SELECTED), 'Duplicate source foods'
GROUPS = {'1': '乳制品', '2': '油脂调味', '4': '油脂调味', '5': '肉蛋鱼', '8': '主食', '9': '水果',
          '10': '肉蛋鱼', '11': '蔬菜', '12': '坚果', '13': '肉蛋鱼', '14': '饮品', '15': '肉蛋鱼',
          '16': '豆类', '17': '肉蛋鱼', '18': '主食', '19': '零食', '20': '主食', '25': '零食'}

with zipfile.ZipFile(ARCHIVE) as archive:
    def rows(name):
        filename = next(name_in_zip for name_in_zip in archive.namelist() if name_in_zip.endswith('/' + name))
        return csv.DictReader(io.TextIOWrapper(archive.open(filename), encoding='utf-8-sig'))
    mapping = {int(row['NDB_number']): row['fdc_id'] for row in rows('sr_legacy_food.csv')}
    wanted = {mapping[ndb] for ndb, _, _ in SELECTED}
    metadata = {row['fdc_id']: row for row in rows('food.csv') if row['fdc_id'] in wanted}
    nutrition = {fdc: {} for fdc in wanted}
    for row in rows('food_nutrient.csv'):
        if row['fdc_id'] in wanted and row['nutrient_id'] in {'1008', '1003', '1004', '1005'}:
            nutrition[row['fdc_id']][row['nutrient_id']] = float(row['amount'])
    catalog = []
    for ndb, name, aliases in SELECTED:
        fdc = mapping[ndb]
        nutrients = nutrition[fdc]
        assert '1008' in nutrients, f'Missing kcal: {name}'
        category = GROUPS.get(metadata[fdc]['food_category_id'], '其他')
        if metadata[fdc]['description'].startswith('Egg,'): category = '肉蛋鱼'
        if '花生仁' in name: category = '坚果'
        catalog.append(dict(id='usda-' + fdc, name=name, aliases=aliases,
                            category=category,
                            kcalPer100g=nutrients['1008'], protein=nutrients.get('1003'),
                            fat=nutrients.get('1004'), carbs=nutrients.get('1005'),
                            reference='USDA SR Legacy 2018 / FDC ' + fdc,
                            description=metadata[fdc]['description']))
all_names = [name for food in catalog for name in set([food['name']] + food['aliases'])]
assert len(set(all_names)) == len(all_names), 'Ambiguous names or aliases'
assert all(0 <= food['kcalPer100g'] <= 1000 for food in catalog)
destination = ROOT / 'android/app/src/main/assets/foods.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Generated {len(catalog)} foods with source IDs: {destination}')
